from __future__ import annotations

import io
import os
import sys
import unittest
import urllib.error
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import api_client


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class RetryOnTransientErrors(unittest.TestCase):
    def setUp(self):
        # Real backoff would make a 3-attempt test take 1s+2s -- irrelevant to
        # what's being tested, so it's mocked out everywhere in this class.
        self._sleep_patch = patch("api_client.time.sleep")
        self._sleep_patch.start()

    def tearDown(self):
        self._sleep_patch.stop()

    def test_succeeds_on_first_try_with_no_retry(self):
        with patch("urllib.request.urlopen", return_value=FakeResponse(b"ok")) as mock_open:
            req = urllib.request.Request("https://example.invalid")
            resp = api_client._urlopen_with_retry(req, timeout=5)
            self.assertEqual(resp.read(), b"ok")
            self.assertEqual(mock_open.call_count, 1)

    def test_retries_transient_error_then_succeeds(self):
        calls = {"n": 0}

        def flaky(req, timeout):
            calls["n"] += 1
            if calls["n"] < 3:
                raise TimeoutError("stalled")
            return FakeResponse(b"ok")

        with patch("urllib.request.urlopen", side_effect=flaky):
            req = urllib.request.Request("https://example.invalid")
            resp = api_client._urlopen_with_retry(req, timeout=5)
            self.assertEqual(resp.read(), b"ok")
            self.assertEqual(calls["n"], 3)

    def test_gives_up_after_max_attempts(self):
        with patch("urllib.request.urlopen", side_effect=TimeoutError("stalled")) as mock_open:
            req = urllib.request.Request("https://example.invalid")
            with self.assertRaises(TimeoutError):
                api_client._urlopen_with_retry(req, timeout=5)
            self.assertEqual(mock_open.call_count, api_client.RETRY_ATTEMPTS)

    def test_connection_error_is_retried(self):
        with patch("urllib.request.urlopen", side_effect=ConnectionResetError("reset")) as mock_open:
            req = urllib.request.Request("https://example.invalid")
            with self.assertRaises(ConnectionResetError):
                api_client._urlopen_with_retry(req, timeout=5)
            self.assertEqual(mock_open.call_count, api_client.RETRY_ATTEMPTS)

    def test_http_error_is_never_retried(self):
        def raise_http_error(req, timeout):
            raise urllib.error.HTTPError("https://example.invalid", 404, "not found", {}, io.BytesIO(b""))

        with patch("urllib.request.urlopen", side_effect=raise_http_error) as mock_open:
            req = urllib.request.Request("https://example.invalid")
            with self.assertRaises(urllib.error.HTTPError):
                api_client._urlopen_with_retry(req, timeout=5)
            self.assertEqual(mock_open.call_count, 1)  # not retried at all


if __name__ == "__main__":
    unittest.main()
