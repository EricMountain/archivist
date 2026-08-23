data "aws_caller_identity" "current" {}

locals {
  app = var.app_name

  # prod is unsuffixed: photo-archivist-media.
  # anything else is inserted:  photo-archivist-dev-media.
  name_prefix = var.environment == "prod" ? local.app : "${local.app}-${var.environment}"

  # Bucket names are globally unique, so they carry the account ID.
  bucket_suffix = data.aws_caller_identity.current.account_id

  # The instance's own domain is always permitted; var.web_origins adds to it rather
  # than replacing it, so an operator can't lock themselves out by setting it.
  web_origins = concat(["https://${var.domain_name}"], var.web_origins)

  tags = {
    Application = local.app
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  # Attributes projected into both GSIs. Enough to paint a grid cell without a
  # second read, decryption material included. Deliberately excludes `path`, so a
  # rename never touches an index. See docs/design/design.md.
  grid_projection = [
    "thumbs",
    "encDek",
    "encKeyId",
    "width",
    "height",
    "mime",
    "tzOffsetMin",
    "status",
  ]
}
