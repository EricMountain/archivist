# The web app's origin bucket — today just a placeholder page and the discovery
# document every client fetches first. See "The app must find the backend" in
# docs/design/deployment.md.

resource "aws_s3_bucket" "web" {
  bucket = "${local.name_prefix}-web-${local.bucket_suffix}"
}

resource "aws_s3_bucket_public_access_block" "web" {
  bucket                  = aws_s3_bucket.web.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "web" {
  bucket = aws_s3_bucket.web.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "web" {
  bucket = aws_s3_bucket.web.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

# A real web client isn't in scope for this plan (see "Out of scope" in
# docs/plans/01-aws-backend.md) — this is just enough that the domain resolves to
# something instead of a CloudFront error page.
resource "aws_s3_object" "index" {
  bucket       = aws_s3_bucket.web.id
  key          = "index.html"
  content_type = "text/html"
  content      = <<-HTML
    <!doctype html>
    <html>
      <head><meta charset="utf-8"><title>${var.instance_name}</title></head>
      <body>
        <p>${var.instance_name} is running. There is no web client yet —
        use the Archivist Android app, pointed at this domain.</p>
      </body>
    </html>
  HTML

  lifecycle {
    ignore_changes = [content] # an operator's own web app deploy takes over this key
  }
}

# GET https://<domain>/.well-known/archivist.json — public and unauthenticated,
# containing no secrets, only the coordinates needed to attempt a login. See
# "The app must find the backend" in deployment.md.
resource "aws_s3_object" "wellknown" {
  bucket        = aws_s3_bucket.web.id
  key           = ".well-known/archivist.json"
  content_type  = "application/json"
  cache_control = "public, max-age=300"

  content = jsonencode({
    apiBase = "https://${var.domain_name}/api"
    region  = var.aws_region
    cognito = {
      userPoolId = aws_cognito_user_pool.users.id
      clientId   = aws_cognito_user_pool_client.app.id
    }
    cryptoVersion = 1
    instanceName  = var.instance_name
  })
}
