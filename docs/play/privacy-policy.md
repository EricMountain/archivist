# Privacy Policy — Archivist (Android app)

**Effective date:** [DATE]
**Last updated:** [DATE]

## In short

Archivist is a self-hosted photo backup app. It connects to a server **you** run in
**your own** AWS account. Your photos go to your storage, under your control.

The author of Archivist receives no data from you. There is no Archivist service, no
account with us, and no server of ours involved at any point. We could not see your
photos even if we wanted to, because we have no idea where they are.

This policy describes the app. If you use someone else's Archivist server, that
person's own policy governs what they do with what you upload — ask them for it.

## Who is responsible for your data

**You are**, or whoever operates the server you connect to.

Archivist is software. When you deploy it, you become the data controller for
everything it stores: it runs in your AWS account, under your domain, with credentials
only you hold. The author is a software supplier, not a processor and not a controller,
because there is nothing for them to process — no telemetry, no phone-home, no shared
backend.

The author is [NAME], contactable at [EMAIL] about the software itself.

## What the app sends, and where

Archivist transmits, **only to the server address you enter**:

- Your photos and videos, encrypted on your device before they leave it
- Reduced-size previews, encrypted the same way
- Information about your photos: dates, image dimensions, file size and type, camera
  make and model, folder and file names, search labels, and albums or favourites you
  create
- The full technical metadata block from your camera (EXIF), **encrypted** — this is
  where GPS coordinates live, if your camera recorded them
- Your account details on that server: email address, display name, sign-in identifiers

That destination is a server you chose. The app has no default server and no fallback.

## What the app sends to the author

Nothing.

No analytics, no crash reports, no diagnostics, no usage statistics, no device
identifiers, no advertising identifiers, no "anonymous" telemetry. The app contains no
third-party SDK that transmits anything anywhere.

The only network requests Archivist makes are to the server address you configured.

## Encryption, and what your server can see

Photos and videos are encrypted on your device using a key generated on your device.
The key is held by your enrolled devices and your recovery code, and is never
transmitted to any server. Your server stores ciphertext it cannot decrypt.

Some information is stored in **readable** form, because searching and browsing require
it. Even on your own server, it is worth knowing which:

**Readable:** dates and time zone offsets, image dimensions and file sizes, camera make
and model and lens, folder and file names, search labels, album names and favourites,
and a keyed fingerprint of each file used to avoid duplicates.

**Encrypted:** the photos and videos themselves, their previews, and the raw camera
metadata block including GPS coordinates.

In practice, that means a folder named "Hospital appointments" is readable, and a photo
labelled "wedding" is readable, while the photograph itself is not.

## We cannot recover your data

There is no password reset for encrypted content, no support process that can decrypt a
library, and no back door — not for the author, not for the server operator, not for
AWS.

Keep your recovery code somewhere safe, and keep an independent copy of photos you
cannot afford to lose.

## Permissions the app asks for

**Photos and videos (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`)** — to read the photos in
the folders you choose to back up. Archivist only uploads from folders you have
selected. Denying this prevents backup from working.

**Notifications** — to show upload progress and let you stop a running backup.

Archivist never requests location permission and never reads your device's GPS. The only
location data involved is what your camera already wrote into a photo, and that is
encrypted before upload.

## Data stored on your device

Archivist keeps a local cache of your library — thumbnails, metadata, an upload queue,
and a record of files you have deleted so they are not re-uploaded. All of it lives in
the app's private storage, and all of it is removed when you uninstall the app or clear
its data.

## Children

Archivist is not directed at children under 13.

## Changes to this policy

If this policy changes materially, the date above is updated and the app notifies you
before the change takes effect. Past versions are listed at the end.

## Contact

[EMAIL] — for questions about the app.

For questions about a specific server's data handling, contact whoever runs it. If
that's you, it's your call.

---

# Notes before publishing — not part of the policy

Delete this section before making the document public.

**Placeholders:** `[DATE]`, `[NAME]`, `[EMAIL]`.

**This is the app policy, and it is deliberately narrow.** It covers software that
receives nothing. The separate obligations that come with *running an instance for other
people* are in `instance-privacy-policy.md`. Anyone running an instance for others needs
that one as well, and the two documents should not be merged.

**No account deletion URL is needed here.** Play's account-deletion requirement applies
to accounts *the developer* creates and holds. Accounts here exist on the user's own
server, and deletion means deleting their own data or destroying their own deployment.
Say so plainly if a reviewer asks; the in-app account deletion still exists.

**Controller identity.** The author is named as a software supplier rather than a
controller, so a role address (`privacy@…`) on your own domain is enough. This is a much
lighter obligation than the previous, service-operator framing.

**Self-hosting is what makes this policy strong**, and it can be stated confidently.
"The author receives nothing" is unusual and true. Don't hedge it into vagueness.

**Revise this policy if any of the following happen:**

| Change | What breaks |
| --- | --- |
| Adding crash reporting or analytics of any kind | "The app sends nothing to the author" — the single strongest claim here |
| An update server, licence check, or version ping | Same |
| A default or fallback server address | "The app has no default server" |
| Rekognition, or any server-side pixel processing | "Your server stores ciphertext it cannot decrypt" |
| Reverse-geocoded place labels | Location would become readable on the server |

The first two are worth guarding carefully. A single analytics SDK, added casually,
invalidates the central claim of this document.

**Keep aligned with `data-safety.md`.** Reviewers read both.

# Version history

- [DATE] — first published.
