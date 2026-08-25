data "aws_caller_identity" "current" {}

locals {
  app = var.app_name

  # prod is unsuffixed: archivist-media.
  # anything else is inserted:  archivist-dev-media.
  name_prefix = var.environment == "prod" ? local.app : "${local.app}-${var.environment}"

  # Bucket names are globally unique, so they carry the account ID.
  bucket_suffix = data.aws_caller_identity.current.account_id

  # The instance's own domain is always permitted; var.web_origins adds to it rather
  # than replacing it, so an operator can't lock themselves out by setting it.
  web_origins = concat(["https://${var.domain_name}"], var.web_origins)

  # Best-effort guess at the Route 53 zone that owns domain_name: strip the first
  # label ("photos.example.com" -> "example.com"). Wrong for an apex domain or a
  # zone delegated below the second level — var.route53_zone_name overrides it.
  domain_labels = split(".", var.domain_name)
  zone_name = coalesce(
    var.route53_zone_name,
    join(".", slice(local.domain_labels, 1, length(local.domain_labels))),
  )

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
