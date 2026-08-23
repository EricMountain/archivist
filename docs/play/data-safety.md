# Play Data Safety declaration — Archivist

Answers to transcribe into the Play Console form for `fr.enry.archivist`, with the
reasoning kept alongside so a future release can tell whether an answer still holds.

**Re-check this file whenever the app gains a dependency that phones home**, adds
analytics or crash reporting, or changes what metadata leaves the device. Those are the
three things that invalidate the answers below.

## The self-hosting complication

Archivist sends data to a server the **user** operates, in the user's own AWS account.
The developer receives nothing and has no access to any instance.

Play defines collection as *transmitting data off the device*, and that definition
doesn't ask who receives it. So the conservative and defensible reading is: **declare
the data as collected**, and use the privacy policy — which reviewers read — to explain
that the destination is infrastructure the user controls.

The alternative reading, which several self-hosted apps take, is to declare no
collection on the grounds that the developer collects nothing. It's arguable, but an
under-declaration is an enforcement risk and an over-declaration is not. Declaring is
cheap here, because the answers are good anyway.

*Worth confirming against current Play guidance before submitting — this is the one
answer in this document that rests on interpretation rather than fact.*

Nothing is "shared" under either reading: the recipient is the user's own
infrastructure, and AWS is a processor.

## Overview

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** — transmitted to the user's own server |
| Is all user data collected by your app encrypted in transit? | **Yes** — HTTPS throughout; image bytes and raw EXIF are additionally encrypted client-side before upload |
| Do you provide a way for users to request their data be deleted? | **Yes** — in-app account deletion; the data lives on the user's own server |

## Data types

Every row below is **not shared**: data goes to the user's own deployment, and AWS acts
as a processor for that deployment rather than as a third party.

| Data type | Collected | Purpose | Required? |
| --- | --- | --- | --- |
| **Personal info → Name** | Yes | App functionality, Account management | Optional (display name) |
| **Personal info → Email address** | Yes | App functionality, Account management | Required |
| **Personal info → User IDs** | Yes | App functionality, Account management | Required |
| **Photos and videos → Photos** | Yes | App functionality | Required |
| **Photos and videos → Videos** | Yes | App functionality | Required |
| **Location → Approximate location** | Yes | App functionality | Optional |
| **Location → Precise location** | Yes | App functionality | Optional |
| **App activity → Other actions** | No | — | — |
| **App info and performance → Crash logs** | No | — | — |
| **App info and performance → Diagnostics** | No | — | — |
| **Device or other IDs** | No | — | — |

Nothing is collected for Analytics, Advertising, Personalisation or Fraud prevention.
There is no third-party SDK in the app that transmits anything.

## The answers that need explaining

### Location — yes, and neither encryption nor self-hosting changes that

Photos carry GPS coordinates in EXIF, and the app reads them: to derive the UTC offset
when a camera records no `OffsetTimeOriginal`. That is collection of **precise
location**, and it must be declared even though the user never grants a location
permission and the app never reads the device's GPS.

The EXIF blob is encrypted client-side and goes only to the user's own server, so
neither the developer nor the server can read those coordinates. **This still does not
make the answer "no."** Collection is transmission off the device, regardless of who
receives it or who can read it — the same reason photos are declared despite being
ciphertext.

Approximate location is declared alongside precise, since a coordinate trivially yields
one.

### Photos and videos — collected, but not readable by us

The form has no "end-to-end encrypted" option, so photos must be declared as collected
even though the server holds only ciphertext and no key. Declaring otherwise would be
wrong: the data does leave the device.

Say so in the privacy policy and the store listing instead, where there's room for it.
"Encrypted in transit" is the closest the form gets, and it's answered yes.

### Crash logs and diagnostics — currently no

There is no crash reporter or analytics SDK today. Adding one is allowed and normal;
see "Adding crash reporting" below for what it changes.

### Device or other IDs — no

The `deviceKey` in the data model is derived from EXIF camera make, model and serial —
a property of the *camera that took the photo*, not an identifier of the phone. No
advertising ID, no ANDROID_ID, no device fingerprint is collected.

## Obligations beyond the form

1. **A privacy policy at a public URL**, linked in the listing — `privacy-policy.md`.
   Not optional, and checked at review.
2. **Account deletion.** Play requires apps supporting account creation to offer
   deletion in-app and, normally, through a publicly reachable web URL. That web
   requirement is aimed at developer-held accounts, and here there are none: accounts
   exist on servers the developer cannot reach. In-app deletion exists, and destroying
   the deployment removes everything. Be ready to explain this in review notes rather
   than hoping it goes unnoticed — a reviewer looking for a deletion URL and not finding
   one will reject first and ask later.
3. **Deletion must mean deletion.** The archive keeps ~100-byte purge tombstones after
   photos are erased (see `design.md`). Account deletion has to remove those too, or
   the claim isn't accurate.

Operators running an instance for other people have their own obligations, which are not
the developer's — see `instance-privacy-policy.md`.

## Adding crash reporting

Entirely permitted, and no obstacle to acceptance — most apps ship one. What it changes:

* **App info and performance → Crash logs** becomes Yes, purpose *App functionality* or
  *Diagnostics*.
* **Device or other IDs** likely becomes Yes. Firebase Crashlytics collects a Firebase
  installation ID; most hosted reporters attach some install identifier. This is the row
  people forget, and it's the one that makes a declaration inaccurate.
* The "no third-party SDK transmits anything" property is spent.

The risks are specific to this app rather than generic:

1. **Stack traces leak user content.** Exception messages and breadcrumbs routinely
   carry file paths, and here a path is `2026/07-japan/IMG_4021.HEIC` — user data, and
   exactly the kind the rest of the design goes to trouble to protect. Scrub paths and
   filenames before sending, and never log them at all in release builds.
2. **Native crash capture can dump memory holding key material.** The master key lives
   in memory by design. Don't enable NDK crash reporting, or ensure minidumps are
   disabled.
3. **A reporter that uploads on any network** undercuts the metered-data policy. Whatever
   is chosen must respect the same constraints as sync.

**Recommendation: self-hosted.** A Sentry instance on your own hardware, or ACRA posting to
our own endpoint, keeps crash data inside the same trust boundary as everything else,
avoids the Device IDs row entirely, and preserves the "no third party receives anything"
claim. That coherence is worth more here than Crashlytics' convenience, in an app whose
entire premise is not handing data to people who don't need it.

Make it **opt-in** with a real settings toggle, and declare the collection as optional.
Declaring optional requires the toggle to genuinely exist.

## If Rekognition is ever used

Not a Play problem, and now it's not even the developer's decision — it's a per-instance
choice an operator makes in their own account. The Data Safety answers above don't
change.

The cost is to a *claim*. Right now the policy can say the server stores ciphertext it
cannot decrypt, and that's true. Routing pixels through Rekognition means a transient
decrypt, and it becomes "your server decrypts photos briefly to label them" — materially
weaker, and it must be written that way in whichever policy applies.

Encrypting the EXIF blob doesn't affect this either way: Rekognition reads pixels, not
metadata. The two decisions are independent.

## What good faith looks like here

Most apps fill this form defensively. This one has genuinely strong answers — no
analytics, no ad IDs, no third-party SDKs, client-side encryption, no developer-held
backend at all — and they're worth stating plainly rather than minimally.

Two places to resist looking better than reality: **location**, where the honest answer
is yes, and **collection**, where "self-hosted so we collect nothing" is a tempting
reading of a definition that doesn't actually say that.
