# Play Console: Photos and Videos permissions declaration

Answers for the "Photos and Videos" declaration (App content → Photo and video
permissions). Justification and rationale live in `docs/design/android.md`, "Media
permissions"; this file is the paste-ready text.

Re-check whenever the app's use of media permissions changes: what it reads, which
folders, whether it reads outside user-nominated folders, or whether any data leaves the
device other than to the user's own instance. Keep consistent with `data-safety.md` and
`privacy-policy.md`.

## READ_MEDIA_IMAGES — "Describe your app's use of the permission"

The console field is limited to **250 characters** (248 here):

> Core function: backup/sync of the user's photos to their own self-hosted storage. A background worker reads new photos in user-chosen folders with no user present; the photo picker can't do unattended sync. Files are encrypted on-device pre-upload.

Longer points, if a reviewer asks: the user nominates which folders sync (the app does
not upload all media); nothing is sent to the developer or any third party; photos are
used only to upload to the user's own instance, never for analytics or advertising; the
permission can be revoked at any time, and partial access on Android 14+ is supported.

## READ_MEDIA_VIDEO

Same text, with "videos" in place of "photos" (re-check the 250-character count).

## Review video

Google may ask for a short demo. Show: the folder selection screen, the permission
prompt, and a photo syncing.

## ACCESS_MEDIA_LOCATION

Believed not to be part of this declaration (unconfirmed against a real submission; see
`docs/design/android.md`). If the console asks: the app reads EXIF GPS data so the
location can be stored in the encrypted metadata, it's optional, and the user can deny
it.
