"""Points a handful of tests at a real source corpus for an extra, best-effort
confidence check, without hardcoding any one person's local directory into a
committed file (see CLAUDE.md's "Nothing personal in the committed tree").

Set `ARCHIVIST_IMPORT_TEST_CORPUS` to a local directory of real photos/videos to
opt in; every test that uses this skips itself when it's unset, exactly like
`test/lambda/*`/`test/repo/*` skip themselves when this repo's DynamoDB
Local/MinIO env vars aren't set (tools/local-infra.sh).
"""

from __future__ import annotations

import os

ENV_VAR = "ARCHIVIST_IMPORT_TEST_CORPUS"


def real_corpus_dir() -> str | None:
    path = os.environ.get(ENV_VAR)
    if path and os.path.isdir(path):
        return path
    return None
