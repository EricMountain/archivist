# Privacy notice — [INSTANCE NAME]

*Template for an operator running an Archivist instance that other people use. If you
run an instance only for yourself, you don't need this. If you have given anyone else an
account, you probably do.*

*Replace the placeholders, delete the italic notes, and publish it where your users can
reach it.*

---

**Effective date:** [DATE]
**Last updated:** [DATE]

## What this is

[INSTANCE NAME] is a private Archivist server at **[DOMAIN]**, run by [OPERATOR NAME]
for a small number of invited people. It is not a public service and accounts are not
open to registration.

Archivist is self-hosted software. This notice covers **this instance** — what is stored
here, who can see it, and what happens to it. The app's own policy covers the software.

## Who is responsible

[OPERATOR NAME], contactable at [EMAIL], operates this server and is the data
controller for what it holds.

The infrastructure runs in [OPERATOR NAME]'s AWS account in [REGION]. AWS acts as a
processor: it hosts the data and does nothing else with it.

## What is stored here

**Encrypted, and unreadable by the operator:** your photos and videos, their previews,
and the raw camera metadata block including any GPS coordinates.

**Readable by the operator**, because search and browsing need it:

- Dates, times and time zone offsets
- Image dimensions, file sizes, file types
- Camera make, model and lens
- Folder and file names — for example `2026/07-japan/IMG_4021.HEIC`
- Search labels such as "beach" or "dog"
- Album names and favourites
- Your email address, display name and sign-in identifiers

*Be honest with your users about this. Folder names and labels can be revealing even
when the photographs are not.*

## What the operator can and cannot do

The operator holds the AWS account, so they can see everything in the readable list
above, and can delete anything on the server.

They **cannot** view your photos. Files are encrypted on your device with a key held
only by you and your enrolled devices. The operator has no copy of that key and no way
to obtain one.

They cannot recover your photos either. If you lose your recovery code and your enrolled
devices, the data on this server becomes permanently unreadable — to you and to
everyone.

## Sharing

Nothing on this instance is shared with anyone. There is no advertising, no analytics,
no tracking, and no third party involved beyond the hosting provider named above.

Other users of this instance cannot see your photos. Each account is a separate library.

## How long data is kept

Deleted photos go to a trash area and are permanently erased after 30 days.

After erasure, a small non-reversible record of the file's fingerprint is kept so that a
file you deliberately deleted is not re-uploaded by your devices on the next sync. It
cannot be used to reconstruct the photo.

Your account and its data are kept until you ask for them to be removed, or until this
instance is shut down.

## Deleting your data

Delete individual photos in the app at any time.

To delete your whole account and its data, ask [EMAIL], or use [DELETION URL] if one is
provided. *An operator running an instance for a handful of known people can reasonably
handle this by email; write down which you offer.*

## If this instance shuts down

*Say what you will actually do — users deserve to know, and it costs nothing to state.*

[OPERATOR NAME] will give at least [NOTICE PERIOD] notice before shutting this instance
down, so you can export your library. Keep your own copy of anything important
regardless: this is a personal server run as a favour, not a commercial service with
uptime guarantees.

## Your rights

If you are in the EU or UK you have the right to access your data, correct it, delete
it, obtain a portable copy, and complain to a data protection authority — in France,
the CNIL (cnil.fr).

Export is available in the app. For anything else, write to [EMAIL].

## Contact

[EMAIL]

---

# Notes for the operator — not part of the notice

**Do you need this at all?** Running an instance purely for yourself and your household
is likely covered by GDPR's household exemption. Giving accounts to friends outside your
household is a grey area that this notice resolves cheaply — publishing it costs an
afternoon and removes the question. Opening registration to the public would put you
firmly outside the exemption, with the full controller obligations that implies.

*Not legal advice.*

**Keep the promises you can keep.** The shutdown-notice section is the one people
actually rely on. Don't promise a notice period you won't honour.

**Revise this if you** enable server-side labelling that decrypts photos, add analytics,
change region, or open registration.
