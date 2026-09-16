"""Everything this tool needs to talk to a real instance: the discovery document
(docs/design/deployment.md "The app must find the backend"), Cognito's raw
unsigned JSON-RPC API (mirroring android's CognitoAuthApi.kt/CognitoAuthClient.kt
byte-for-byte, stdlib-only rather than an AWS SDK -- see that file's own comment on
why: `application/x-amz-json-1.1` and an `X-Amz-Target` header, no SigV4), and the
archivist API itself (docs/design/api.md).

This is the "third party" design.md's Interoperability section describes: "a
script that authenticates, encrypts locally, and PUTs to presigned URLs -- the
same path the Android app takes, with no privileged position." Nothing here calls
DynamoDB or S3 directly except via presigned PUT URLs the API itself hands back.

**Every `urlopen` call passes an explicit timeout.** Found the hard way: an
unattended multi-hour run over a real network hit a connection that completed its
TCP handshake and then went silent (a laptop sleep/wake, in that instance) --
`urlopen` with no timeout blocks on a stalled `read()` forever, `socket`'s
default. This is a socket-level *inactivity* timeout, not a cap on total request
duration: a large streaming PUT that's genuinely still sending bytes never trips
it, only a connection with zero progress in either direction for the whole
window does. `Importer.run`'s existing per-file `try/except` already turns a
raised timeout into one recorded error rather than aborting the run, so this
alone is enough to keep an unattended run alive across a single bad connection
-- no retry logic needed on top of that.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass

# Inactivity timeout, seconds -- see the module docstring above. Long enough that
# a slow-but-progressing large streaming PUT (a multi-hundred-MB video, a slow
# connection) is never mistaken for a hang; short enough that a genuinely stalled
# connection fails within a run's ordinary per-file cadence rather than sitting
# there for hours.
DEFAULT_TIMEOUT_SECONDS = 60


class ApiError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body}")
        self.status = status
        self.body = body


def _post_json(url: str, headers: dict, payload: dict) -> dict:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=DEFAULT_TIMEOUT_SECONDS) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, e.read().decode("utf-8", errors="replace")) from None


# --- Discovery document (deployment.md "The app must find the backend") ---------


@dataclass(frozen=True)
class Instance:
    api_base: str
    region: str
    user_pool_id: str
    client_id: str
    crypto_version: int
    instance_name: str


def fetch_discovery(host: str) -> Instance:
    url = f"https://{host}/.well-known/archivist.json"
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=DEFAULT_TIMEOUT_SECONDS) as resp:
        doc = json.loads(resp.read().decode("utf-8"))
    if doc.get("cryptoVersion") != 1:
        raise RuntimeError(
            f"instance advertises cryptoVersion {doc.get('cryptoVersion')!r}; "
            "this tool only implements crypto-format.md v1 and must refuse rather "
            "than write data it can't read back"
        )
    return Instance(
        api_base=doc["apiBase"],
        region=doc["region"],
        user_pool_id=doc["cognito"]["userPoolId"],
        client_id=doc["cognito"]["clientId"],
        crypto_version=doc["cryptoVersion"],
        instance_name=doc.get("instanceName", host),
    )


# --- Cognito (raw, unsigned JSON-RPC -- see module docstring) -------------------


def _cognito_idp_url(region: str) -> str:
    return f"https://cognito-idp.{region}.amazonaws.com/"


def _cognito_call(region: str, action: str, payload: dict) -> dict:
    headers = {
        "Content-Type": "application/x-amz-json-1.1",
        "X-Amz-Target": f"AWSCognitoIdentityProviderService.{action}",
    }
    return _post_json(_cognito_idp_url(region), headers, payload)


@dataclass
class CognitoSession:
    access_token: str
    id_token: str
    refresh_token: str | None
    expires_in: int


class NewPasswordRequired(Exception):
    """Raised by `sign_in_with_password` when the account still has a Cognito
    admin-set temporary password (docs/ops/create-user.md's
    `admin-create-user --temporary-password` flow) -- carries the session token
    `complete_new_password` needs to finish the challenge."""

    def __init__(self, session: str):
        super().__init__("account has a temporary password; a new one is required")
        self.session = session


def sign_in_with_password(region: str, client_id: str, username: str, password: str) -> CognitoSession:
    resp = _cognito_call(
        region,
        "InitiateAuth",
        {
            "AuthFlow": "USER_AUTH",
            "ClientId": client_id,
            "AuthParameters": {
                "USERNAME": username,
                "PASSWORD": password,
                "PREFERRED_CHALLENGE": "PASSWORD",
            },
        },
    )
    return _handle_auth_response(resp)


def complete_new_password(region: str, client_id: str, username: str, new_password: str, session: str) -> CognitoSession:
    resp = _cognito_call(
        region,
        "RespondToAuthChallenge",
        {
            "ClientId": client_id,
            "ChallengeName": "NEW_PASSWORD_REQUIRED",
            "Session": session,
            "ChallengeResponses": {"USERNAME": username, "NEW_PASSWORD": new_password},
        },
    )
    return _handle_auth_response(resp)


def refresh_session(region: str, client_id: str, refresh_token: str) -> CognitoSession:
    resp = _cognito_call(
        region,
        "InitiateAuth",
        {
            "AuthFlow": "REFRESH_TOKEN_AUTH",
            "ClientId": client_id,
            "AuthParameters": {"REFRESH_TOKEN": refresh_token},
        },
    )
    session = _handle_auth_response(resp)
    if session.refresh_token is None:
        # Cognito doesn't rotate refresh tokens by default -- keep using the one
        # the caller already has, same as Android's ArchivistApiFactory does.
        session.refresh_token = refresh_token
    return session


def _handle_auth_response(resp: dict) -> CognitoSession:
    result = resp.get("AuthenticationResult")
    if result is not None:
        return CognitoSession(
            access_token=result["AccessToken"],
            id_token=result["IdToken"],
            refresh_token=result.get("RefreshToken"),
            expires_in=result["ExpiresIn"],
        )
    challenge = resp.get("ChallengeName")
    if challenge == "NEW_PASSWORD_REQUIRED":
        raise NewPasswordRequired(resp["Session"])
    raise RuntimeError(f"unexpected Cognito challenge: {challenge!r} (expected a direct sign-in or NEW_PASSWORD_REQUIRED)")


# --- The archivist API itself (docs/design/api.md) -------------------------------


class ArchivistApi:
    def __init__(self, instance: Instance, session: CognitoSession, username: str, password: str | None):
        self.instance = instance
        self.session = session
        self._username = username
        self._password = password  # only kept for a from-scratch re-auth if refresh itself fails

    # -- low-level ---------------------------------------------------------------

    def _request(self, method: str, path: str, body: dict | None = None, retry_on_401: bool = True) -> dict | None:
        url = f"{self.instance.api_base}{path}"
        headers = {"Authorization": f"Bearer {self.session.access_token}"}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=DEFAULT_TIMEOUT_SECONDS) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            if e.code == 401 and retry_on_401:
                self._reauthenticate()
                return self._request(method, path, body, retry_on_401=False)
            if e.code == 404:
                return None
            raise ApiError(e.code, e.read().decode("utf-8", errors="replace")) from None

    def _reauthenticate(self) -> None:
        if self.session.refresh_token:
            try:
                self.session = refresh_session(
                    self.instance.region, self.instance.client_id, self.session.refresh_token
                )
                return
            except ApiError:
                pass  # refresh token itself expired/revoked -- fall through to a full re-login
        if self._password is None:
            raise RuntimeError("session expired and no password was retained to re-authenticate")
        self.session = sign_in_with_password(
            self.instance.region, self.instance.client_id, self._username, self._password
        )

    # -- routes --------------------------------------------------------------

    def session_bootstrap(self, home_tz: str | None = None) -> dict:
        body = {"homeTz": home_tz} if home_tz else {}
        return self._request("POST", "/session/bootstrap", body)

    def get_keys(self, wrap_id: str | None = None) -> list[dict]:
        path = "/keys" if wrap_id is None else f"/keys?wrapId={wrap_id}"
        return self._request("GET", path)["wraps"]

    def post_key_version(self) -> dict:
        return self._request("POST", "/keys/version", {})

    def post_key(self, body: dict) -> dict:
        return self._request("POST", "/keys", body)

    def get_hash_secret(self) -> dict | None:
        """None on 404 -- design.md: absent until the first client has ever set one."""
        return self._request("GET", "/keys/hash-secret")

    def put_hash_secret(self, enc_hash_secret: str, hash_secret_key_id: str) -> None:
        self._request(
            "PUT",
            "/keys/hash-secret",
            {"encHashSecret": enc_hash_secret, "hashSecretKeyId": hash_secret_key_id},
        )

    def get_settings(self) -> dict:
        return self._request("GET", "/settings")

    def post_upload(self, body: dict) -> dict:
        return self._request("POST", "/uploads", body)


def put_bytes(url: str, content_type: str, data: bytes) -> None:
    """PUTs to a presigned S3 URL -- SigV4 auth is baked into the query string, so
    no Authorization header at all (docs/design/api.md: "Uploads never go through
    CloudFront... the client PUTs ciphertext bytes directly to S3 using AWS SigV4
    query-string auth baked into that URL")."""
    req = urllib.request.Request(url, data=data, headers={"Content-Type": content_type}, method="PUT")
    try:
        # A longer inactivity window than DEFAULT_TIMEOUT_SECONDS: this is an
        # up-to-hundreds-of-MB body (the corpus this tool was built against tops
        # out under 1 GB), and the timeout is per blocking send()/recv() call, not
        # a cap on the whole transfer -- so this only ever fires on a genuinely
        # stalled connection, never on a slow-but-progressing upload.
        with urllib.request.urlopen(req, timeout=300):
            pass
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, e.read().decode("utf-8", errors="replace")) from None
