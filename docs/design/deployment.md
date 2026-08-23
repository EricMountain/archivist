# Deployment model

Archivist is **self-hosted**. Each user runs the whole stack — DynamoDB table, S3
buckets, Lambdas, CloudFront, Cognito — in their own AWS account, under their own
domain. There is no shared service and no multi-tenant backend.

`photos.example.com` is one such deployment. It is not "the service"; it's the author's own
instance, open to a small number of invited people and nobody else.

## Three roles, deliberately separate

| Role | Who | Responsibility |
| --- | --- | --- |
| **Author** | whoever writes the code | Ships an app and Terraform. Receives no user data, ever. |
| **Operator** | whoever runs `terraform apply` | Owns the AWS account, the data, and the legal responsibility for it |
| **User** | whoever uses the app | Signs in to an operator's instance |

Most deployments collapse Operator and User into one person backing up their own
photos. That's the expected case, and it's the one the design optimises for.

The distinction matters anyway, because it determines who is accountable for what. The
Author cannot be the data controller for a deployment they have no access to. Under
GDPR the **Operator is the data controller** for their own instance. This isn't a
disclaimer dressed up as architecture — the Author genuinely has no credentials, no
endpoint and no copy.

### When Operator and User are the same person

Running an instance purely for yourself and your household is likely covered by GDPR's
household exemption (Article 2(2)(c)) — processing in the course of a purely personal
activity. Inviting a handful of friends or family probably stays within it; offering
sign-ups to the public would not.

`photos.example.com` is invite-only for exactly this reason, and that's a deliberate choice
rather than a capacity limit. An operator who opens their instance to strangers takes
on the full controller obligations, and should read
`docs/play/instance-privacy-policy.md`.

*Not legal advice — worth confirming if an instance ever grows beyond household use.*

## What an operator provides

* An AWS account. They pay their own bill; see the cost notes in `design.md`.
* A domain, and a certificate for it.
* Roughly fifteen minutes with Terraform.

## What a deployment consists of

```
their-domain.example
├── CloudFront          web app + ciphertext delivery
├── API Gateway         the metadata API
├── Lambda              ingest, trash sweep, key operations
├── DynamoDB            <prefix>-media
├── S3                  <prefix>-originals, <prefix>-derived
└── Cognito             user pool, passkeys, optional Google federation
```

All of it Terraform, all of it parameterised — nothing in the configuration names the
author's domain, account or region.

## The app must find the backend

Archivist ships as one generic Android app that talks to whichever instance you point
it at. It cannot be compiled against a fixed endpoint, and it cannot embed a Cognito
user pool ID, because those differ per deployment.

So the first thing the app asks for is a server address, and it resolves the rest by
fetching a discovery document:

```
GET https://<their-domain>/.well-known/archivist.json

{
  "apiBase":        "https://their-domain.example/api",
  "region":         "eu-west-1",
  "cognito": {
    "userPoolId":   "eu-west-1_XXXXXXXXX",
    "clientId":     "…"
  },
  "cryptoVersion":  1,
  "instanceName":   "Home photos"
}
```

Served publicly and unauthenticated — it contains no secrets, only the coordinates
needed to attempt a login. `cryptoVersion` lets an app refuse an instance it's too old
to talk to, which is the failure worth catching early rather than halfway through an
upload.

This is the Nextcloud and Immich pattern, and it works for the same reasons.

## Consequences for the design

**Single-tenant.** One deployment serves one library, or one household. That doesn't
change the schema — everything is already namespaced by `ownerId` — but it does mean
the scaling concerns in `design.md` are bounded by one family's photo collection rather
than a user base. The GSI1 hot-partition note is now firmly theoretical.

**No cross-instance anything.** No global search, no shared albums between instances,
no central account. A user with access to two instances has two unrelated accounts.

**Upgrades are the operator's problem.** There is no fleet to migrate. Schema changes
must therefore be backward-compatible or come with a documented migration an operator
can run themselves, because instances will lag by months. Version the API and the
crypto format, and never assume the client and backend were deployed the same week.

**The blast radius of a bug is one household.** Worth remembering when weighing how much
defensive engineering a feature deserves.

## Consequences for distribution

The Play listing must say, clearly and early, that **Archivist requires your own AWS
account**. Someone installing it expecting a Google Photos replacement will be
disappointed, leave a one-star review, and be right to. Self-hosted apps that
communicate this well do fine; the ones that bury it get punished.

The store description should carry the requirement in the first two lines, and the app
should say it again on the first screen, before asking for anything.

## What stays centralised

Nothing operational. The author publishes:

* the Android app, through Play
* the Terraform
* the crypto format specification and its conformance test vectors

That last one matters most. Instances will run different versions for years, and the
encrypted-format compatibility problem described in `design.md` becomes a compatibility
problem across *time and deployments*, not just across clients. Test vectors are the
only thing that keeps a 2029 app able to read a library encrypted in 2026.
