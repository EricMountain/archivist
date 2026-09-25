# Rolling out the video preview clip (rebuilds `timeline_gsi`)

**Status: written from the code and Terraform, not from a real apply.** The plan output
and timings below are what to expect and what to check; nothing here has been run against a
deployed instance yet. Update this file with what actually happened the first time.

The video preview clip (design.md, "Video preview clip") adds a `preview` attribute to
`#META` and to `timeline_gsi`'s projection (`local.timeline_projection` in
`terraform/locals.tf`). DynamoDB cannot alter an existing index's projection in place, so
`terraform apply` **deletes and recreates `timeline_gsi`**. `facet_gsi` is unaffected.

## What you'll notice while it happens

* **Timeline queries fail or return nothing** from the moment the old index is dropped
  until the new one has finished backfilling. That is `GET /photos`, `GET /trash`, the
  timeline bounds, and anything else served from `timeline_gsi` — the app's grid will show
  its error/retry state. Direct reads (`GET /photos/{photoId}`, uploads, media, thumbnails)
  don't use the index and keep working.
* Backfill time scales with the number of `#META` items. A household-sized table should
  be minutes, not hours, but that is an expectation, not a measurement.
* Uploads during the window still write `timelinePk`/`timelineSk` on `#META` as usual and
  are picked up by the rebuilt index; nothing is lost.
* Don't run `make backfill-histogram` (it reads `timeline_gsi`) until the index is ACTIVE
  again.

## Order

1. **Rehearse on the dev instance first**, if you have one (`make plan-dev`, then
   `make deploy-dev`). It shares the same Terraform code and is the cheapest place to see
   the real plan output and how long the rebuild takes.
2. **Read the plan** (`cd terraform && terraform workspace select default && terraform
   plan`, run through `make build` first — never plan/apply against a stale build). Expect
   the Lambda code changes plus a change to `aws_dynamodb_table` involving
   `timeline_gsi`'s `non_key_attributes`; expect **no** change to `facet_gsi`. If the plan
   also touches `facet_gsi`, stop and find out why.
3. **Pick a quiet time.** Nobody is using the app for a while.
4. `make deploy`. Watch the table's index status in the AWS console or with
   `aws dynamodb describe-table --table-name <table> --query 'Table.GlobalSecondaryIndexes[].{n:IndexName,s:IndexStatus}'`
   until `timeline_gsi` is `ACTIVE`.
5. Confirm the timeline loads in the app before releasing a client build that uses
   previews.

## Ordering with the Android client

The server change is additive and backward compatible: an older app ignores `preview`, and
a newer app built before the index is rebuilt simply sees no `preview` on timeline rows
(videos keep showing their still). So there is no hard ordering — but ship the server first,
so a client that starts uploading previews never talks to a server that rejects the field.

## Existing videos

They have no preview until repaired. Open a video and use **Repair thumbnails** (which now
also generates the preview), or backfill in bulk from the local backup as any other derived
object change (design.md, "Changing the ladder later").
