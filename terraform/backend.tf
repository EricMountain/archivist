terraform {
  # Partial configuration — the bucket name carries an account ID, so it lives in
  # backend.hcl rather than here.
  #
  #   terraform init -backend-config=backend.hcl
  #
  # State locking uses S3 conditional writes (use_lockfile), so no DynamoDB lock
  # table is needed. Do not add one; it was deprecated for this purpose.
  backend "s3" {}
}
