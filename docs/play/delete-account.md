# Deleting your Archivist account

This page is Archivist's account-deletion web resource, required by Google Play for any
app that supports account creation. It's the same page for every Archivist install,
because Archivist has no accounts of its own to delete — read on.

## There is no Archivist account to delete

Archivist is self-hosted software. There is no central Archivist service and no
developer-operated backend. When you sign in, your account exists only on the one
specific server — the "instance" — you connected to, in that instance's own operator's
AWS account. The author of Archivist has no access to any instance, holds no copy of
your data, and cannot delete anything on your behalf.

So "delete your account" always means: delete your account **on the instance you use**.

## If you can open the app

Open Archivist, go to **Settings → Account → Delete Account**, and confirm by typing
"DELETE". This is immediate and fully self-service: it permanently deletes your entire
library — photos, videos, metadata, and your key material — from that instance. There is
no waiting period and no confirmation email, because none is needed; the deletion
happens as soon as you confirm it.

This is the normal, recommended way to delete your account.

## If you can't open the app

If you've lost your device, uninstalled the app, or otherwise can't sign in, contact
whoever operates the instance you use — the person who invited you, or yourself, if
you're the one running it. They can remove your sign-in and fully delete your library
on their end, without needing you to regain access to the app first.

## Nothing to contact the developer about

Because the developer never holds any account or any data, there is no developer-side
request to make here. Requests go to your instance's operator, as above.
