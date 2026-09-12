"""Orchestration: walks a local directory, resolves metadata for each file the
same way design.md's ladders specify, encrypts client-side, and calls the real
`POST /uploads` handshake -- design.md's "Third parties use the API, not the
datastore": "a script that authenticates, encrypts locally, and PUTs to presigned
URLs -- the same path the Android app takes, with no privileged position."

Stateless across runs by design: resumability comes from the server's own
hash/stem dedup (a re-run re-hashes every file and gets `duplicate`/`resumed`
back for anything already landed), not a local progress file that could drift
from what the server actually has.
"""

from __future__ import annotations

import base64
import json
import os
import sys
import tempfile
from dataclasses import dataclass, field
from datetime import datetime, timezone

import crypto_format as cf
import filename_time
import media_probe
import mp4box
import recovery_code
import takenat_ladder as ladder
import thumbnails
import ulid_gen
from api_client import ApiError, ArchivistApi, put_bytes

SKIP_NAMES = {".DS_Store"}


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def b64d(data: str) -> bytes:
    return base64.b64decode(data)


def to_iso_utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


@dataclass
class Enrollment:
    api: ArchivistApi
    master_key: bytes
    master_key_ver: str
    hash_secret: bytes
    home_tz: str | None
    strip_location: bool
    device_defaults: dict[str, int]


class EnrollmentError(RuntimeError):
    pass


def enroll(api: ArchivistApi, recovery_code_str: str) -> Enrollment:
    """The one master-key route this tool implements -- see recovery_code.py's
    module docstring. Requires the owner to already have enrolled at least one
    other device (Android, in practice): a fresh owner with zero key wrappings
    has no recovery code yet for this tool to consume, and design.md scopes
    *minting* the very first master key/recovery-code pair to whichever client
    does the initial enrolment UI -- deliberately not reimplemented here."""
    api.session_bootstrap()

    wraps = api.get_keys()
    if not wraps:
        raise EnrollmentError(
            "this owner has no enrolled devices yet -- sign in with the Android app "
            "first (it mints the master key and the mandatory recovery code at "
            "enrolment); this tool only consumes an existing recovery code, it "
            "doesn't create the first one"
        )
    recovery_wrap_meta = next((w for w in wraps if w["kind"] == "recovery"), None)
    if recovery_wrap_meta is None:
        raise EnrollmentError(
            "this owner's key list has no recovery wrapping at all, which should be "
            "impossible (design.md: 'at least two wrappings exist at all times, and "
            "one is the recovery code') -- can't proceed without one"
        )

    full = api.get_keys(wrap_id=recovery_wrap_meta["wrapId"])
    recovery_wrap = next(w for w in full if w["wrapId"] == recovery_wrap_meta["wrapId"])

    entropy = recovery_code.verify_and_derive_entropy(recovery_code_str)
    kdf_params = recovery_wrap["kdfParams"]
    kek = recovery_code.argon2id_kek(
        password=entropy.encode("ascii"),
        salt=b64d(recovery_wrap["kdfSalt"]),
        m_kib=recovery_code.parse_memory_kib(kdf_params["m"]),
        t=kdf_params["t"],
        p=kdf_params["p"],
        length=32,
    )
    master_key = cf.unwrap_key(kek, b64d(recovery_wrap["wrappedKey"]))
    master_key_ver = recovery_wrap["masterKeyVer"]

    hash_secret_resp = api.get_hash_secret()
    if hash_secret_resp is not None:
        hash_secret = cf.unwrap_key(master_key, b64d(hash_secret_resp["encHashSecret"]))
    else:
        # design.md: "absent until the first client has ever set one" -- this run
        # is that first client. Real, but rare: every account this tool expects to
        # run against already has an Android client that set this long ago.
        hash_secret = os.urandom(32)
        api.put_hash_secret(b64(cf.wrap_key(master_key, hash_secret)), master_key_ver)

    settings = api.get_settings()
    device_defaults = {
        d["deviceKey"]: d["tzOffsetMin"]
        for d in _get_devices(api)
        if d.get("tzOffsetMin") is not None
    }

    return Enrollment(
        api=api,
        master_key=master_key,
        master_key_ver=master_key_ver,
        hash_secret=hash_secret,
        home_tz=settings.get("homeTz"),
        strip_location=bool(settings.get("stripLocationOnUpload")),
        device_defaults=device_defaults,
    )


def _get_devices(api: ArchivistApi) -> list[dict]:
    resp = api._request("GET", "/devices")  # no dedicated wrapper -- one call site
    return resp.get("devices", []) if resp else []


@dataclass
class Stats:
    created: int = 0
    attached: int = 0
    duplicate: int = 0
    resumed: int = 0
    restored: int = 0
    trashed_blocked: int = 0
    tombstone_skipped: int = 0
    errors: list[tuple[str, str]] = field(default_factory=list)
    no_timestamp_evidence: int = 0
    gps_rung_used: int = 0
    owner_default_rung_used: int = 0
    video_without_thumbnail: int = 0
    location_stripped: int = 0
    raw_uploaded_without_location_strip: int = 0

    def total_ok(self) -> int:
        return self.created + self.attached + self.duplicate + self.resumed + self.restored


def device_key_of(exif: media_probe.ExifData) -> str | None:
    parts = [exif.camera_make, exif.camera_model, exif.camera_serial]
    normalised = [(_norm(p)) for p in parts]
    if normalised == ["-", "-", "-"]:
        return None
    return "|".join(normalised)


def _norm(part: str | None) -> str:
    if not part or not part.strip():
        return "-"
    return " ".join(part.strip().lower().split())


class Importer:
    def __init__(
        self,
        enrollment: Enrollment,
        source_root: str,
        *,
        dry_run: bool = False,
        restore_trashed: bool = False,
        upload_offset: ladder.UploadOffsetHint | None = None,
        progress=lambda msg: print(msg, file=sys.stderr),
    ):
        self.enrollment = enrollment
        self.source_root = os.path.abspath(source_root)
        self.dry_run = dry_run
        self.restore_trashed = restore_trashed
        self.upload_offset = upload_offset
        self.progress = progress
        self.stats = Stats()
        # (dir, stem) -> photoId, populated as each file finishes (whatever the
        # outcome), used to resolve `-edited`-family `groupWith` targets.
        self._photo_id_by_stem: dict[tuple[str, str], str] = {}

    def run(self, limit: int | None = None) -> Stats:
        files = self._discover_files()
        files.sort(key=lambda rel: len(media_probe.base_stem_chain(_stem(rel))))
        if limit is not None:
            files = files[:limit]

        for i, rel_path in enumerate(files, start=1):
            self.progress(f"[{i}/{len(files)}] {rel_path}")
            try:
                self._process_one(rel_path)
            except Exception as e:  # noqa: BLE001 -- one bad file must not abort the run
                self.stats.errors.append((rel_path, f"{type(e).__name__}: {e}"))
                self.progress(f"  ERROR: {type(e).__name__}: {e}")
        return self.stats

    def _discover_files(self) -> list[str]:
        out = []
        for root, _dirs, names in os.walk(self.source_root):
            for name in names:
                if name in SKIP_NAMES or name.startswith("."):
                    continue
                if name.lower().endswith(".json"):
                    continue  # a Takeout sidecar, if one somehow ended up here
                full = os.path.join(root, name)
                rel = os.path.relpath(full, self.source_root).replace(os.sep, "/")
                out.append(rel)
        return out

    def _group_with_for(self, rel_path: str) -> str | None:
        dirname, stem, _ext = _split(rel_path)
        chain = media_probe.base_stem_chain(stem)
        if len(chain) < 2:
            return None
        return self._photo_id_by_stem.get((dirname, chain[1]))

    def _process_one(self, rel_path: str) -> None:
        group_with = self._group_with_for(rel_path)
        photo_id = self._upload_one(rel_path, group_with)
        if photo_id is not None:
            dirname, stem, _ext = _split(rel_path)
            self._photo_id_by_stem[(dirname, stem)] = photo_id

    # -- the actual per-file pipeline ---------------------------------------

    def _upload_one(self, rel_path: str, group_with: str | None) -> str | None:
        abs_path = os.path.join(self.source_root, rel_path)
        _dirname, _stem, ext = _split(rel_path)
        basename = os.path.basename(rel_path)

        working_path = abs_path
        cleanup_path: str | None = None
        try:
            exif: media_probe.ExifData | None = None
            video: media_probe.VideoProbeResult | None = None

            if media_probe.is_video(ext):
                video = media_probe.probe_video(abs_path)
                if self.enrollment.strip_location:
                    cleanup_path = _temp_path_for(abs_path)
                    if mp4box_strip(abs_path, cleanup_path):
                        self.stats.location_stripped += 1
                        working_path = cleanup_path
                    else:
                        working_path = cleanup_path  # still a safe copy, just nothing to strip
            elif media_probe.is_image(ext):
                exif = media_probe.extract_exif(abs_path)
                if self.enrollment.strip_location and exif.has_gps:
                    cleanup_path = _temp_path_for(abs_path)
                    media_probe.strip_gps_from_image(abs_path, cleanup_path)
                    self.stats.location_stripped += 1
                    working_path = cleanup_path
                    exif = media_probe.extract_exif(working_path)
            elif media_probe.is_raw(ext) and self.enrollment.strip_location:
                # Pillow can't decode RAW at all (media_probe.is_image excludes
                # it), so this tool has no way to read -- let alone strip -- a
                # RAW file's own embedded GPS tags. A real, visible gap: flagged
                # in the run's own summary rather than silently uploading it
                # unstripped with no record that happened.
                self.stats.raw_uploaded_without_location_strip += 1

            plain_bytes = os.path.getsize(working_path)
            content_hash = cf.content_hash_of_file(self.enrollment.hash_secret, working_path)
            mime = media_probe.mime_for_ext(ext)

            resolved, taken_at, taken_at_src, tz_offset_min, tz_src = self._resolve_timestamp(
                basename, working_path, exif, video
            )
            if not resolved:
                self.stats.no_timestamp_evidence += 1
            if tz_src == "gps":
                self.stats.gps_rung_used += 1
            elif tz_src == "owner-default":
                self.stats.owner_default_rung_used += 1

            width, height = self._dimensions(exif, video)
            device_key = device_key_of(exif) if exif else None

            chunk_size = cf.choose_chunk_size(plain_bytes)
            candidate_photo_id = ulid_gen.new_ulid()
            candidate_dek = os.urandom(32)

            exif_enc = self._encrypt_exif_blob(exif, candidate_dek, candidate_photo_id)

            thumb_list = self._generate_thumbnails(working_path, ext, video)
            encrypted_thumbs = {
                t.size: (
                    t,
                    cf.encrypt_object(
                        candidate_dek, cf.aad(candidate_photo_id, cf.object_ref_thumbnail(t.size)), t.bytes_, 0
                    ),
                )
                for t in thumb_list
            }
            if media_probe.is_video(ext) and not thumb_list:
                self.stats.video_without_thumbnail += 1

            candidate_iv = os.urandom(12) if chunk_size == 0 else None

            body: dict = {
                "path": rel_path,
                "plainBytes": plain_bytes,
                "bytes": cf.ciphertext_length(plain_bytes, chunk_size),
                "mime": mime,
                "width": width,
                "height": height,
                "contentHash": content_hash,
                "takenAt": to_iso_utc(taken_at),
                "takenAtSrc": taken_at_src,
                "tzOffsetMin": tz_offset_min,
                "tzSrc": tz_src,
                "encDek": b64(cf.wrap_key(self.enrollment.master_key, candidate_dek)),
                "encKeyId": self.enrollment.master_key_ver,
                "encChunkSize": chunk_size,
                "photoId": candidate_photo_id,
            }
            if candidate_iv is not None:
                body["encIv"] = b64(candidate_iv)
            if device_key:
                body["deviceKey"] = device_key
            if exif_enc is not None:
                body["exifEnc"] = b64(exif_enc.ciphertext)
                body["exifIv"] = b64(exif_enc.iv)
            if encrypted_thumbs:
                body["thumbs"] = {
                    str(size): {"bytes": len(enc.ciphertext), "iv": b64(enc.iv)}
                    for size, (_t, enc) in encrypted_thumbs.items()
                }
            if group_with:
                body["groupWith"] = group_with
            if self.restore_trashed:
                body["reAddDeleted"] = True

            if self.dry_run:
                self.progress(
                    f"  DRY RUN: would upload {rel_path} "
                    f"(takenAt={body['takenAt']} src={taken_at_src}/{tz_src}"
                    f"{' groupWith=' + group_with if group_with else ''})"
                )
                return None

            resp = self.enrollment.api.post_upload(body)
            return self._handle_response(
                resp, working_path, mime, chunk_size, candidate_dek, candidate_iv, encrypted_thumbs
            )
        finally:
            if cleanup_path is not None and os.path.exists(cleanup_path):
                os.remove(cleanup_path)

    def _resolve_timestamp(self, basename, working_path, exif, video):
        filename_local = filename_time.parse(basename)
        file_mtime = None
        if video is not None and video.creation_time_utc is not None:
            # A container-recorded creation instant plays the same structural role
            # as a client filesystem mtime (an absolute instant, no offset
            # evidence of its own) and is materially better evidence than this
            # tool's own real source corpus's mtimes, which are all identical
            # (the extraction date) -- see this change's commit message. It rides
            # in the same `file_mtime` ladder slot rather than inventing a sixth
            # TakenAtSrc value for it.
            file_mtime = video.creation_time_utc
        else:
            file_mtime = datetime.fromtimestamp(os.path.getmtime(working_path), tz=timezone.utc)

        result = ladder.resolve(
            exif_date_time_original=exif.date_time_original if exif else None,
            exif_offset_time_original=exif.offset_time_original if exif else None,
            exif_gps_utc=exif.gps_date_time_utc if exif else None,
            filename_local=filename_local,
            file_mtime=file_mtime,
            upload_offset=self.upload_offset,
            device_default_offset_min=self._device_default(exif),
            home_tz=self.enrollment.home_tz,
        )
        if result is None:
            now = datetime.now(timezone.utc)
            return False, now, "upload", 0, "assumed-utc"
        return True, result.taken_at, result.taken_at_src, result.tz_offset_min, result.tz_src

    def _device_default(self, exif) -> int | None:
        if exif is None:
            return None
        key = device_key_of(exif)
        if key is None:
            return None
        return self.enrollment.device_defaults.get(key)

    def _dimensions(self, exif, video) -> tuple[int, int]:
        if exif is not None and exif.width and exif.height:
            return exif.width, exif.height
        if video is not None and video.width and video.height:
            return video.width, video.height
        return 1, 1

    def _encrypt_exif_blob(self, exif, candidate_dek, candidate_photo_id):
        if exif is None:
            return None
        blob = {
            k: v
            for k, v in {
                "cameraMake": exif.camera_make,
                "cameraModel": exif.camera_model,
                "cameraSerial": exif.camera_serial,
                "lens": exif.lens,
                "dateTimeOriginal": exif.date_time_original,
                "offsetTimeOriginal": exif.offset_time_original,
                "gpsDateTimeUtc": exif.gps_date_time_utc.isoformat() if exif.gps_date_time_utc else None,
            }.items()
            if v is not None
        }
        if not blob:
            return None
        plaintext = json.dumps(blob, separators=(",", ":")).encode("utf-8")
        return cf.encrypt_object(candidate_dek, cf.aad(candidate_photo_id, cf.object_ref_exif()), plaintext, 0)

    def _generate_thumbnails(self, working_path, ext, video) -> list[thumbnails.Thumbnail]:
        if media_probe.is_video(ext):
            duration = None  # not probed separately; ffmpeg seeks near the start regardless
            got = thumbnails.generate_for_video(working_path, duration)
            return got or []
        if media_probe.is_image(ext):
            try:
                return thumbnails.generate_for_image(working_path)
            except Exception:
                return []  # an undecodable "image" extension (rare) -- no thumbnails, not fatal
        return []

    def _handle_response(self, resp, working_path, mime, chunk_size, candidate_dek, candidate_iv, encrypted_thumbs):
        if resp.get("skipped"):
            self.stats.tombstone_skipped += 1
            return None

        photo_id = resp.get("photoId")
        rendition_id = resp.get("renditionId")
        original = resp.get("originalUpload")

        if original is None or rendition_id is None:
            if resp.get("trashed"):
                self.stats.trashed_blocked += 1
            elif resp.get("restored"):
                self.stats.restored += 1
            else:
                self.stats.duplicate += 1
            return photo_id

        created = resp.get("created") is True
        resumed = resp.get("resumed") is True

        if created:
            dek, effective_chunk_size, iv, upload_thumbs = candidate_dek, chunk_size, candidate_iv, True
            self.stats.created += 1
        elif resumed:
            dek = cf.unwrap_key(self.enrollment.master_key, b64d(resp["encDek"]))
            effective_chunk_size = resp["encChunkSize"]
            iv = b64d(resp["encIv"]) if effective_chunk_size == 0 else None
            upload_thumbs = True
            self.stats.resumed += 1
        else:
            dek = cf.unwrap_key(self.enrollment.master_key, b64d(resp["encDek"]))
            effective_chunk_size = chunk_size
            iv = candidate_iv
            upload_thumbs = resp.get("becomesPrimary") is True
            self.stats.attached += 1

        aad = cf.aad(photo_id, cf.object_ref_rendition(rendition_id))
        with open(working_path, "rb") as f:
            plaintext = f.read()
        if effective_chunk_size == 0:
            ciphertext = cf.whole_object_encrypt(dek, iv, aad, plaintext)
        else:
            ciphertext = cf.streaming_encrypt(dek, aad, plaintext)
        put_bytes(original["url"], mime, ciphertext)

        if upload_thumbs:
            thumb_uploads = resp.get("thumbUploads") or {}
            for size, (thumb, enc) in encrypted_thumbs.items():
                url = thumb_uploads.get(str(size))
                if not url:
                    continue
                if created:
                    payload = enc.ciphertext
                else:
                    payload = cf.whole_object_encrypt(
                        dek, enc.iv, cf.aad(photo_id, cf.object_ref_thumbnail(size)), thumb.bytes_
                    )
                put_bytes(url, "image/webp", payload)

        return photo_id


def mp4box_strip(src: str, dst: str) -> bool:
    return mp4box.strip_video_location(src, dst)


def _split(rel_path: str) -> tuple[str, str, str]:
    dirname = os.path.dirname(rel_path)
    filename = os.path.basename(rel_path)
    stem, dot, ext = filename.rpartition(".")
    if not dot:
        return dirname, filename, ""
    return dirname, stem, ext.lower()


def _stem(rel_path: str) -> str:
    return _split(rel_path)[1]


def _temp_path_for(original_path: str) -> str:
    fd, path = tempfile.mkstemp(prefix="archivist-import-", suffix=os.path.splitext(original_path)[1])
    os.close(fd)
    return path
