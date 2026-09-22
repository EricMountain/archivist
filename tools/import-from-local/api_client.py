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
window does.

**Every call also retries a few times on a transient connection failure**
(timeout, reset, DNS hiccup -- never a real HTTP response, `HTTPError` is
never retried here) before giving up. Found the hard way too, the very next
run after the timeout fix above: `dedupe_by_filename.py --execute` re-verifying
2,242 duplicate groups live is thousands of sequential calls over one long
run, and a single transient blip anywhere in that -- now correctly a raised
exception instead of an infinite hang -- was enough to kill the whole process
outright, since nothing downstream of a bare `api.get_photo_detail(...)` call
was wrapping it. A caller (e.g. `Importer.run`'s per-file loop, or
`dedupe_by_filename.py`'s per-group revalidation) should still expect this to
raise and have its own `try/except` around a unit of work that must survive
one failure -- retrying here only absorbs a blip *within* one call, it doesn't
turn a sustained outage into a guarantee.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

# Inactivity timeout, seconds -- see the module docstring above. Long enough that
# a slow-but-progressing large streaming PUT (a multi-hundred-MB video, a slow
# connection) is never mistaken for a hang; short enough that a genuinely stalled
# connection fails within a run's ordinary per-file cadence rather than sitting
# there for hours.
DEFAULT_TIMEOUT_SECONDS = 60

# Retry policy for a transient connection failure -- see the module docstring.
# 3 attempts, 1s/2s backoff: enough to absorb a real blip without turning a
# script that's actually offline into a multi-minute hang before it says so.
RETRY_ATTEMPTS = 3
RETRY_BACKOFF_SECONDS = 1.0

# Never retried -- a real HTTP response (including an error one) is not a
# connection failure, and 401/404/etc. already have their own defined meaning
# to the caller. `HTTPError` is technically a `URLError`/`OSError` subclass,
# which is exactly why this has to be excluded explicitly rather than relying
# on catching "the opposite" of it.
_TRANSIENT_ERRORS = (TimeoutError, ConnectionError, urllib.error.URLError, OSError)


def _urlopen_with_retry(req: urllib.request.Request, timeout: float):
    for attempt in range(1, RETRY_ATTEMPTS + 1):
        try:
            return urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError:
            raise  # a real response, including an error one -- callers handle this themselves
        except _TRANSIENT_ERRORS:
            if attempt == RETRY_ATTEMPTS:
                raise
            time.sleep(RETRY_BACKOFF_SECONDS * attempt)


class ApiError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body}")
        self.status = status
        self.body = body


def _post_json(url: str, headers: dict, payload: dict) -> dict:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with _urlopen_with_retry(req, DEFAULT_TIMEOUT_SECONDS) as resp:
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
    with _urlopen_with_retry(req, DEFAULT_TIMEOUT_SECONDS) as resp:
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
            with _urlopen_with_retry(req, DEFAULT_TIMEOUT_SECONDS) as resp:
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

    def get_photos_page(self, cursor: str | None = None, limit: int = 200) -> dict:
        """One page of the live timeline (api.md `GET /photos`) -- `{items, cursor}`,
        `cursor` absent once there's no more. Order doesn't matter for a full-library
        walk, so this doesn't pass `order`/`from`/`to` at all."""
        params = {"limit": str(limit)}
        if cursor:
            params["cursor"] = cursor
        return self._request("GET", f"/photos?{urllib.parse.urlencode(params)}")

    def get_photo_detail(self, photo_id: str) -> dict | None:
        """`{meta, renditions, facets}` -- the raw items, not `dto.ts` DTOs (api.md:
        `GET /photos/{photoId}`). None if the asset doesn't exist (or isn't this
        owner's) rather than raising, since a caller walking a photoId list already
        knows it existed a moment ago and 404 here is a real, meaningful answer.
        Works identically for a trashed asset -- `getAssetPartition` server-side is
        a plain Query with no `deletedAt`/status filter; trashed just means it's no
        longer reachable via `GET /photos`'s own live-timeline listing."""
        return self._request("GET", f"/photos/{photo_id}")

    def get_photo_by_path(self, path: str) -> dict | None:
        """Exact-path lookup (api.md `GET /photos/by-path`) -- one GetItem against
        the PATH pointer, live or trashed (trashing never touches PATH pointers).
        None on 404: nothing is filed under that exact path -- never uploaded,
        the wrong path, or purged long ago. Returns `{photoId, renditionId}`, not
        full detail -- chain to `get_photo_detail(photoId)` for that. The point
        of this call is to *avoid* `get_photos_page`/`get_trash_page`: a single
        GetItem instead of paging through the whole live-or-trashed listing to
        find one file by name."""
        params = {"path": path}
        return self._request("GET", f"/photos/by-path?{urllib.parse.urlencode(params)}")

    def get_trash_page(self, cursor: str | None = None, limit: int = 200) -> dict:
        """One page of trashed assets (api.md `GET /trash`) -- same lean shape as
        `get_photos_page`, plus `blockedAttempts`/`lastAttemptAt`/`lastAttemptBy`
        when a source has tried to re-upload a trashed photo's content since."""
        params = {"limit": str(limit)}
        if cursor:
            params["cursor"] = cursor
        return self._request("GET", f"/trash?{urllib.parse.urlencode(params)}")

    def post_photo_thumbs(self, photo_id: str, thumbs: dict) -> dict:
        """Repairs one or more thumbnail rungs (api.md `POST /photos/{photoId}/thumbs`)
        -- `thumbs` maps each size (`"256"`/`"1024"`/`"2048"`) to `{"bytes": N, "iv":
        b64}`, the same descriptor shape `POST /uploads` itself uses for thumbnails.
        Returns `{"thumbUploads": {size: presigned PUT url}}`. Presigns against a
        *fresh* S3 key per call, never the plain upload-time one (design.md: the
        upload-time key is cached a year at the CloudFront edge) -- callers don't need
        to know this, but it's why a repair is never just overwriting a cached URL."""
        return self._request("POST", f"/photos/{photo_id}/thumbs", {"thumbs": thumbs})

    def post_rendition_replace(self, photo_id: str, rendition_id: str, body: dict) -> dict:
        """Replaces one rendition's stored bytes in place (api.md `POST
        /photos/{photoId}/renditions/{renditionId}/replace`, design.md "Replacing a
        rendition's bytes") -- `body` is `{contentHash, plainBytes, bytes, mime,
        width, height, encIv?, encChunkSize}`. Returns `{"uploadUrl": ...}`, a
        presigned PUT against the rendition's *existing* S3 key -- unlike thumbnail
        repair, there's no fresh key to mint here (`/media/*` is `caching_disabled`
        at the CloudFront edge, so there's no stale response to dodge)."""
        return self._request("POST", f"/photos/{photo_id}/renditions/{rendition_id}/replace", body)

    def patch_taken_at(self, photo_id: str, taken_at: str, tz_offset_min: int) -> None:
        """Manually corrects `takenAt`/`tzOffsetMin` (api.md `PATCH /photos/{photoId}`,
        design.md "Manually correcting takenAt") -- `taken_at` a UTC ISO-8601 instant,
        `tz_offset_min` the offset in minutes. Works on a trashed asset too. Sets both
        `takenAtSrc`/`tzSrc` to `manual` server-side, which permanently outranks every
        automatic source -- a later-attached rendition's own EXIF can't silently
        overwrite this."""
        self._request("PATCH", f"/photos/{photo_id}", {"takenAt": taken_at, "tzOffsetMin": tz_offset_min})

    def delete_photo(self, photo_id: str, deleted_by: str | None = None) -> None:
        """Trashes the whole asset -- every rendition, not just one -- via the same
        soft-delete `DELETE /photos/{photoId}` route the app's own delete button
        uses (design.md "Trash and deletion"): recoverable via `POST
        .../restore` for `trashRetentionDays` (owner setting, default 30), after
        which a daily sweep purges the S3 objects and most DynamoDB rows for real."""
        body = {"deletedBy": deleted_by} if deleted_by else {}
        self._request("DELETE", f"/photos/{photo_id}", body)


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
        with _urlopen_with_retry(req, 300):
            pass
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, e.read().decode("utf-8", errors="replace")) from None
