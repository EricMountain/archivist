# Creating a user

Archivist is invite-only (see "Inviting someone" in `deployment.md`): self-service
sign-up is disabled on purpose, so the operator creates each account explicitly with
`aws cognito-idp admin-create-user`. This doc is the step-by-step version — commands
verified against a real deployment, not inferred from the SDK docs.

Everything here uses placeholder values (`photos.example.com`,
`eu-west-1_XXXXXXXXX`, `someone@example.com`). Substitute your own; never commit the
real ones — see "Nothing personal in the committed tree" in `CLAUDE.md`.

## 1. Find your user pool ID and app client ID

Four ways, easiest first:

**From the discovery document.** It's public and unauthenticated by design (the app
needs it to attempt a login), so this works from any machine with no AWS credentials
at all:

```sh
curl -s https://photos.example.com/.well-known/archivist.json | jq
```

```json
{
  "apiBase": "https://photos.example.com/api",
  "cognito": { "userPoolId": "eu-west-1_XXXXXXXXX", "clientId": "abc123..." },
  ...
}
```

**From Terraform**, if you have the state (`cd terraform` after `source
private/instance/env.sh`):

```sh
terraform output cognito_user_pool_id
terraform output cognito_user_pool_client_id
```

**From the AWS CLI**, by name, if you have neither of the above to hand. Pools are
named `<prefix>-users` — `archivist-users` for a `prod` deployment, `archivist-dev-users`
for a non-prod one (see `local.name_prefix` in `terraform/locals.tf`):

```sh
aws cognito-idp list-user-pools --max-results 20 \
  --query "UserPools[?contains(Name, 'archivist')].{Name:Name,Id:Id}"
```

**From the AWS Console** — Cognito → User pools → the one named `archivist-users`.

## 2. Create the account

```sh
aws cognito-idp admin-create-user \
  --user-pool-id eu-west-1_XXXXXXXXX \
  --username someone@example.com \
  --user-attributes Name=email,Value=someone@example.com Name=email_verified,Value=true \
  --message-action SUPPRESS \
  --temporary-password 'some-temporary-password'
```

Notes on the flags:

- `--username` is the email address, even though Cognito's internal username is an
  opaque UUID — the user pool has `username_attributes = ["email"]`, so email works as
  an alias at creation and for every sign-in afterward.
- `Name=email_verified,Value=true` skips Cognito's own verification email. You're
  vouching for the address by inviting it; there's no separate confirmation step.
- `--message-action SUPPRESS` stops Cognito emailing the temporary password itself —
  Cognito's default invite email is unbranded and not app-aware. Omit this flag (and
  `--temporary-password`, letting Cognito generate one) if you'd rather it email the
  invite directly; the template is set on the user pool if you want to customise it.
  Either way the account lands in `FORCE_CHANGE_PASSWORD` state until first sign-in.
- Deliver the temporary password to the person out of band (Signal, in person, whatever
  channel you'd trust with a password) if you suppressed the email.

Confirm it worked:

```sh
aws cognito-idp list-users --user-pool-id eu-west-1_XXXXXXXXX \
  --filter 'email = "someone@example.com"'
# UserStatus should read FORCE_CHANGE_PASSWORD
```

## 3. Verifying the account works (optional, operator-side)

There's no client app to hand the invitee yet. Plan 02 step 2.4 (Authentication) has
code now but nothing runnable — no build has ever been installed on a device, and
passkey creation is additionally blocked on a Digital Asset Links file that doesn't
exist yet (open question 2 in `design.md`). Key enrolment (step 2.5) is still not
started. Until both are real, "sign in" isn't something a real person can usefully do.
But you *can* verify the account
and the backend's session-bootstrap step work, entirely from the CLI, without needing
WebAuthn (the user pool's `sign_in_policy` allows plain password as a first factor,
precisely so this kind of check doesn't need a browser):

```sh
CLIENT_ID=abc123...   # from step 1

# Sign in with the temporary password — Cognito will demand a new one.
aws cognito-idp initiate-auth \
  --client-id "$CLIENT_ID" \
  --auth-flow USER_AUTH \
  --auth-parameters USERNAME=someone@example.com,PASSWORD='some-temporary-password',PREFERRED_CHALLENGE=PASSWORD
# → { "ChallengeName": "NEW_PASSWORD_REQUIRED", "Session": "..." }

# Respond with a real password, using the Session value from above.
aws cognito-idp respond-to-auth-challenge \
  --client-id "$CLIENT_ID" \
  --challenge-name NEW_PASSWORD_REQUIRED \
  --session "<Session from above>" \
  --challenge-responses USERNAME=someone@example.com,NEW_PASSWORD='a-real-password'
# → { "AuthenticationResult": { "IdToken": "...", "AccessToken": "...", ... } }
```

Either `IdToken` or `AccessToken` works as a bearer token against the API (API Gateway's
JWT authorizer accepts both for a Cognito-issued token). Confirm bootstrap mints exactly
one library:

```sh
curl -s -X POST https://photos.example.com/api/session/bootstrap \
  -H "Authorization: Bearer <IdToken>" \
  -d '{"homeTz":"UTC","displayName":"Someone"}'
# → {"userId":"01...","ownerId":"01...","created":true}

# Calling it again with the same token is idempotent:
curl -s -X POST https://photos.example.com/api/session/bootstrap \
  -H "Authorization: Bearer <IdToken>" \
  -d '{"homeTz":"UTC","displayName":"Someone"}'
# → {"userId":"01...","ownerId":"01...","created":false}   ← same ids, created: false
```

This is genuinely how their account and library get provisioned — `POST
/session/bootstrap` is what any future client calls on first sign-in. It's just that
right now you're the client, for verification purposes. If you do this against a real
invitee's account rather than a throwaway one, leave the resulting library in place;
don't delete it (see "Cleaning up" below for how, if you do need to).

## 4. What "enrolling" a device means, and why it isn't usable yet

This is the part the design calls **key custody and enrolment** (see that section in
`design.md`), and it's real cryptography that has to happen inside a trusted client —
never something to script by hand, and not just because no client exists yet. Sketched,
so this doc is accurate about what's coming rather than silent about it:

1. The client calls `POST /keys/version` once, to allocate the first master key version
   (`mk-1`).
2. It generates a random 256-bit master key **locally** — the server never sees it,
   ever.
3. It wraps that key twice: once to the device (Android Keystore, or a browser's
   non-extractable WebCrypto key) and once to a recovery code, and uploads both via
   `POST /keys`. **Enrolment isn't complete until the recovery code is confirmed
   back** — there's no support path if it's lost.
4. It generates the owner's hash secret (for `contentHash` dedup) and stores it wrapped,
   via `PUT /keys/hash-secret`.

**None of this is possible today.** Plan 02 step 2.5 (Key enrolment and recovery code)
is `not started` per `STATUS.md`, and step 2.4 (Authentication), while coded, has never
run on a device — there is no installable Android build, no web client, nothing that
can generate or hold a master key on a user's behalf. An account created with this doc
can sign in (step 3 above, via the CLI) and has a library,
but cannot yet upload anything or hold key material. Don't promise an invitee more than
that until plan 02 catches up — update this section (and `STATUS.md`) when it does.

## Troubleshooting

**Wrong pool / can't find it.** Multiple deployments (`prod` plus a `dev` environment,
say) each get their own pool, named after `local.name_prefix` in `terraform/locals.tf`.
`list-user-pools` (step 1) shows every one in the account; check the domain in the
discovery document is the one you meant.

**Resend the invite** (temporary password expired, or they lost it) — same call, with
`RESEND` instead of `SUPPRESS`:

```sh
aws cognito-idp admin-create-user \
  --user-pool-id eu-west-1_XXXXXXXXX \
  --username someone@example.com \
  --message-action RESEND
```

**Invited the wrong person, or by mistake, and they never signed in** (still
`FORCE_CHANGE_PASSWORD`, no library yet — safe to just remove):

```sh
aws cognito-idp admin-delete-user \
  --user-pool-id eu-west-1_XXXXXXXXX \
  --username someone@example.com
```

**They'd already signed in and have a library.** There's no admin-side "delete this
other person's library" endpoint — `DELETE /account` (plan step 1.15) is self-service
only, requiring the owner's own JWT and an explicit confirmation. Deleting the Cognito
user alone leaves their DynamoDB partition and S3 objects behind. If this comes up for
real, treat it as a gap worth closing (an admin-initiated deletion path) rather than
hand-deleting table rows.
