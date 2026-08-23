# Two buckets, because originals and derivatives want different storage classes,
# different CloudFront behaviours and different replication targets — and because a
# separate derived bucket makes changing the thumbnail ladder a bucket-scoped job.

# ---------------------------------------------------------------------------
# Originals — client-side encrypted image bytes. Large, cold, tiered.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "originals" {
  bucket = "${local.name_prefix}-originals-${local.bucket_suffix}"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "originals" {
  bucket                  = aws_s3_bucket.originals.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "originals" {
  bucket = aws_s3_bucket.originals.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "originals" {
  bucket = aws_s3_bucket.originals.id

  versioning_configuration {
    status = "Enabled"
  }
}

# SSE-S3 on top of client-side encryption is redundant — it is not what protects
# these objects, and must not be mistaken for it. Enabled anyway because it costs
# nothing and covers the metadata S3 stores alongside the object.
resource "aws_s3_bucket_server_side_encryption_configuration" "originals" {
  bucket = aws_s3_bucket.originals.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "originals" {
  bucket = aws_s3_bucket.originals.id

  # Orphaned multipart parts are invisible in the console and billed forever.
  # Large video uploads make this a real risk, not a theoretical one.
  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  # Safety net only. The app sets INTELLIGENT_TIERING at PUT time, which avoids the
  # per-object transition request entirely. This catches anything that arrives in
  # STANDARD by another route.
  rule {
    id     = "tier-stragglers"
    status = "Enabled"

    filter {}

    transition {
      days          = 1
      storage_class = "INTELLIGENT_TIERING"
    }
  }

  rule {
    id     = "expire-old-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_retention_days
    }
  }

  depends_on = [aws_s3_bucket_versioning.originals]
}

resource "aws_s3_bucket_cors_configuration" "originals" {
  bucket = aws_s3_bucket.originals.id

  cors_rule {
    allowed_methods = ["GET", "PUT", "HEAD"]
    allowed_origins = local.web_origins
    allowed_headers = ["*"]
    # ETag is required for the browser to complete multipart uploads.
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

# ---------------------------------------------------------------------------
# Derived — encrypted thumbnails at 256/1024/2048. Small, hot, never tiered.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "derived" {
  bucket = "${local.name_prefix}-derived-${local.bucket_suffix}"
}

resource "aws_s3_bucket_public_access_block" "derived" {
  bucket                  = aws_s3_bucket.derived.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "derived" {
  bucket = aws_s3_bucket.derived.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "derived" {
  bucket = aws_s3_bucket.derived.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

# Deliberately no versioning and no tiering here. Thumbnails are regenerable from
# the local originals backup, and objects under 128 KB never transition to a cheaper
# tier anyway — they would pay frequent-access rates plus a monitoring fee for
# nothing. See "Tiering" in docs/design/design.md.
resource "aws_s3_bucket_lifecycle_configuration" "derived" {
  bucket = aws_s3_bucket.derived.id

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_s3_bucket_cors_configuration" "derived" {
  bucket = aws_s3_bucket.derived.id

  cors_rule {
    allowed_methods = ["GET", "PUT", "HEAD"]
    allowed_origins = local.web_origins
    allowed_headers = ["*"]
    max_age_seconds = 3000
  }
}
