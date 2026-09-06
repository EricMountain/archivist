# Deleting a user's account (admin-initiated)

Normal account deletion is self-service, in-app (Settings → Account → Delete Account,
`DELETE /account`) — no operator involvement at all. This doc is for the case that
doesn't cover: someone asks *you* to delete their account and they can't sign into the
app themselves (lost device, no recovery code). It's what
`docs/play/delete-account.md`'s "If you can't open the app" section points to.

Uses `tools/admin-delete-account.mjs`. Like every command in this doc, it runs with
your own AWS credentials against your own account — there's no separate admin role or
API endpoint for this; you already have the access it needs.

Everything here uses placeholder values (`photos.example.com`, `eu-west-1_XXXXXXXXX`,
`someone@example.com`). Substitute your own; never commit the real ones — see "Nothing
personal in the committed tree" in `CLAUDE.md`.

## Before you run this: owner vs. shared access

A person can be the **owner** of their own library, or an **editor/viewer** on someone
else's shared library, or both. Deleting "their account" only means purging DynamoDB
and S3 data for libraries **they own**. For any library they merely have shared access
to, the correct action is revoking that access — the library itself belongs to someone
else and must not be touched. `admin-delete-account.mjs` already makes this
distinction for you automatically; this note is here so you don't second-guess its
output.

## 1. Find your user pool ID and table/bucket names

Same as `create-user.md` step 1 for the pool ID:

```sh
curl -s https://photos.example.com/.well-known/archivist.json | jq
```

And from Terraform, for the table/bucket names the script needs:

```sh
cd terraform && terraform workspace select <the workspace this instance uses>
terraform output -raw media_table_name
terraform output -raw originals_bucket
terraform output -raw derived_bucket
```

## 2. Dry run

```sh
MEDIA_TABLE=$(terraform output -raw media_table_name) \
ORIGINALS_BUCKET=$(terraform output -raw originals_bucket) \
DERIVED_BUCKET=$(terraform output -raw derived_bucket) \
  node ../tools/admin-delete-account.mjs \
    --user-pool-id eu-west-1_XXXXXXXXX \
    --email someone@example.com
```

This deletes nothing. It reports:

- which owned libraries it would fully purge, with item/object counts
- which shared-library memberships it would revoke, and whose library they belong to
  (untouched)
- that it would delete the person's `IDP#` pointer and profile row
- that it would then delete their Cognito user

Read this output before proceeding — especially the "revoke" list, since that's your
confirmation nothing outside this person's own data is about to be touched.

## 3. Actually delete

Same command, with `--yes`:

```sh
MEDIA_TABLE=$(terraform output -raw media_table_name) \
ORIGINALS_BUCKET=$(terraform output -raw originals_bucket) \
DERIVED_BUCKET=$(terraform output -raw derived_bucket) \
  node ../tools/admin-delete-account.mjs \
    --user-pool-id eu-west-1_XXXXXXXXX \
    --email someone@example.com \
    --yes
```

## 4. Confirm it worked

```sh
aws cognito-idp admin-get-user --user-pool-id eu-west-1_XXXXXXXXX --username someone@example.com
# → UserNotFoundException
```

A JWT this person obtained before deletion (if one is still unexpired and you have it
for testing) now resolves to `401 unknown identity` on any `owner`-mode route — the
`IDP#` pointer it depended on is gone.

## If they linked more than one identity provider

The script resolves exactly one identity (the one behind the email you gave it). If
this person also signed in via a second provider (e.g. password *and* Google), that
second `IDP#` pointer isn't found or touched by this run — same limitation
`deleteOwnerData` itself already has for the self-service path (no index from userId to
every IDP pointer a user has). If you know of a second linked identity, its issuer and
subject would need to be resolved and deleted the same way; this hasn't come up in
practice yet.
