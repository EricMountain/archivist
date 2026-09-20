"""The "fetch discovery, sign in" boilerplate every CLI entrypoint in this tool
needs (main.py, dedupe_by_filename.py, inspect_photo.py) -- factored out once
both existed and were about to grow a third near-identical copy.

Credentials are never accepted as bare CLI flags (they'd sit in shell history
and `ps` output): read from the environment, or prompted for with echo off.
"""

from __future__ import annotations

import getpass
import os
import sys
from dataclasses import dataclass

import api_client


def read_secret(env_var: str, prompt: str) -> str:
    value = os.environ.get(env_var)
    if value:
        return value
    return getpass.getpass(prompt)


class AuthFailed(Exception):
    pass


@dataclass
class Session:
    instance: api_client.Instance
    api: api_client.ArchivistApi


def authenticate(host: str, username: str, progress=lambda msg: print(msg, file=sys.stderr)) -> Session:
    """Fetches the discovery document and signs in (handling a Cognito
    NEW_PASSWORD_REQUIRED challenge -- docs/ops/create-user.md's
    `admin-create-user --temporary-password` flow -- transparently). Raises
    AuthFailed with a message ready to print on any failure, rather than
    letting a raw ApiError/network exception surface -- every caller wants the
    same "error: ..." + non-zero-exit shape."""
    progress(f"Fetching discovery document from {host}...")
    try:
        instance = api_client.fetch_discovery(host)
    except Exception as e:
        raise AuthFailed(f"could not fetch https://{host}/.well-known/archivist.json: {e}") from e
    progress(f"  instance: {instance.instance_name} (region {instance.region})")

    password = read_secret("ARCHIVIST_PASSWORD", f"Password for {username}: ")

    progress("Signing in...")
    try:
        session = api_client.sign_in_with_password(instance.region, instance.client_id, username, password)
    except api_client.NewPasswordRequired as challenge:
        new_password = read_secret(
            "ARCHIVIST_NEW_PASSWORD",
            "This account has a temporary password (docs/ops/create-user.md) -- choose a new one: ",
        )
        session = api_client.complete_new_password(
            instance.region, instance.client_id, username, new_password, challenge.session
        )
        password = new_password
    except api_client.ApiError as e:
        raise AuthFailed(f"signing in: {e}") from e

    api = api_client.ArchivistApi(instance, session, username, password)
    return Session(instance, api)
