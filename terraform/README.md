# Terraform

Infrastructure for archivist: the DynamoDB table, the three S3 buckets, Cognito,
the API Gateway HTTP API and its Lambda, the S3-event and purge Lambdas,
CloudFront, DNS and the discovery document.

**This is deployed by each user into their own AWS account.** There is no shared
service — you run the whole stack yourself, under your own domain, and you pay your own
bill. See `docs/design/deployment.md` for what that means and who is responsible for
what.

Nothing in this configuration may hardcode a particular domain, account or region. If
you find something that does, it's a bug.

## Layout

```
terraform/
  bootstrap/       run once, creates the state bucket (local state)
  versions.tf      provider + version pins
  backend.tf       S3 backend, partial config
  variables.tf
  locals.tf        naming convention lives here
  dynamodb.tf      archivist-media
  s3.tf            originals + derived buckets
  cognito.tf       user pool, passkeys, optional Google federation
  iam.tf           one execution role per Lambda, scoped to exactly what it touches
  api.tf           HTTP API, JWT authorizer, the API Lambda and its routes
  s3_events.tf     S3-event Lambda: confirms upload arrival, flips status
  schedules.tf     purge Lambda, daily EventBridge trigger
  cloudfront.tf    certificate, distribution, CDN-prefix-stripping functions
  dns.tf           Route 53 records: ACM validation, the domain alias
  wellknown.tf     web bucket, placeholder page, /.well-known/archivist.json
  outputs.tf
```

Flat root module rather than `modules/` + `environments/`: there is one environment.
Reach for modules when there is a second consumer, not before — premature module
extraction costs more than it saves at this size.

Lambda code is built separately (`make build`, from the repo root) into `dist/`;
each Lambda's Terraform zips its own `dist/<name>/` with `data.archive_file` at
apply time. Never run `terraform apply` without a fresh build first — `make
deploy` is the only supported entry point, for exactly that reason.

## Credentials

Terraform uses the standard AWS credential chain. Set `AWS_PROFILE` in your environment
rather than naming a profile in a `.tf` file:

```sh
export AWS_PROFILE=your-profile
```

An `aws_profile` variable exists for both root modules if you'd rather set it in a
tfvars file, but the environment is preferable — a profile name in a committed file is a
small, avoidable leak about how someone's account is organised.

## First run

You need an AWS account, a domain you control, and credentials as above.

Create a bucket for Terraform state (or provide one):

```sh
cd terraform/bootstrap
terraform init && terraform apply          # creates archivist-tfstate-<account>
```

Configure `backend.hcl` and `tfvars` - you can symlink to a private directory:

```sh
cd ..
cp backend.hcl.example backend.hcl         # set your account ID, region and the tfstate bucket
terraform init -backend-config=backend.hcl

cat > terraform.tfvars <<'EOF'
domain_name = "photos.example.com"
aws_region  = "eu-west-1"
EOF

terraform plan
```

`domain_name` is required and has no default — it's how the CORS rules know which origin
to trust, and later how the app's discovery document is served. `aws_region` defaults to
eu-west-1; pick one near you.

State locking uses S3 conditional writes (`use_lockfile`), so there is **no DynamoDB
lock table**. That approach is superseded; don't reintroduce it.

## Naming

Everything is prefixed `archivist`. `local.name_prefix` in `locals.tf` is the
single place this is decided:

| Environment | Table | Buckets |
| --- | --- | --- |
| `prod` | `archivist-media` | `archivist-originals-<account>` |
| `dev` | `archivist-dev-media` | `archivist-dev-originals-<account>` |

Bucket names carry the account ID because the S3 namespace is global.

## Choosing a region

The default is `eu-west-1` (Ireland). Operators should choose for themselves; the
things worth weighing:

* **Storage price does vary across EU regions.** Ireland (`eu-west-1`) and Stockholm
  (`eu-north-1`) share the cheapest tier at $0.023/GB-month for Standard's first 50 TB;
  Frankfurt, London and Paris are all above it. For an archive that only grows, a few
  percent compounds — check current rates rather than assuming, but expect Ireland or
  Stockholm to win on price.
* **Between Ireland and Stockholm specifically, price is a tie** — same Standard rate,
  same $0.004 at Intelligent-Tiering's Archive Instant tier. Decide those two on the
  grounds below.
* **Keep the primary away from your backups.** If you already back up elsewhere, put
  this in a different region so one regional failure or one bad lifecycle rule can't
  take both. This is the strongest single argument.
* **Service coverage varies.** Smaller regions lag; Rekognition in particular has
  historically not been offered in eu-north-1. Only matters if you want automatic
  labelling. Check the current region table.
* **Latency**, which CloudFront in front of everything makes close to irrelevant.
* **Grid carbon intensity**, if that matters to you — the Nordic regions are markedly
  cleaner than most.

Changing region later is a full data migration, so it's worth ten minutes now.

## Deliberate choices worth not undoing

* **`PAY_PER_REQUEST` on DynamoDB.** A personal library is spiky — idle for days, then
  a 10,000-photo import. Provisioned capacity would need autoscaling config to handle
  the same thing worse.
* **No KMS key on the table.** Omitting `server_side_encryption` uses the AWS-owned
  key, which is free. Enabling it switches to an AWS-managed KMS key billed per
  request. Image bytes are already client-side encrypted, and metadata visibility to
  AWS is an accepted decision, so a KMS key would be pure cost.
* **`abort_incomplete_multipart_upload` on both buckets.** Orphaned multipart parts
  don't appear in the console and are billed indefinitely. With 480 MB video uploads
  from a phone on a flaky connection, this will fire.
* **No versioning on `derived`.** Thumbnails are regenerable from the local originals
  backup; paying to keep old versions of a thumbnail is paying twice for nothing.
* **`prevent_destroy` on the table, originals bucket and state bucket.** Removing
  these is a deliberate act, which is the point.
