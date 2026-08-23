# NOTE: `terraform validate` warns that hash_key/range_key are deprecated in favour of
# a `key_schema` block. As of provider v6.58.0 that replacement does not exist yet —
# both block and attribute forms are rejected as unsupported. The warning is ahead of
# the implementation, so hash_key/range_key stay until key_schema actually ships.
resource "aws_dynamodb_table" "media" {
  name         = "${local.name_prefix}-media"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  range_key    = "sk"

  # Only key attributes are declared. Everything else is schemaless, which is what
  # lets #META, R#, F#, pointer, device and key-wrapping items share one table.
  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  attribute {
    name = "gsi1pk"
    type = "S"
  }

  attribute {
    name = "gsi1sk"
    type = "S"
  }

  attribute {
    name = "gsi2pk"
    type = "S"
  }

  attribute {
    name = "gsi2sk"
    type = "S"
  }

  # Timeline. Sparse: only #META items carry gsi1pk/gsi1sk, so renditions, facets
  # and pointers are structurally incapable of appearing in it.
  global_secondary_index {
    name               = "GSI1"
    hash_key           = "gsi1pk"
    range_key          = "gsi1sk"
    projection_type    = "INCLUDE"
    non_key_attributes = local.grid_projection
  }

  # Facets: labels, camera, device, rendition roles. Sparse over F# items.
  global_secondary_index {
    name               = "GSI2"
    hash_key           = "gsi2pk"
    range_key          = "gsi2sk"
    projection_type    = "INCLUDE"
    non_key_attributes = local.grid_projection
  }

  point_in_time_recovery {
    enabled = true
  }

  # No server_side_encryption block on purpose. Omitting it uses the AWS-owned key,
  # which is free; enabling it switches to an AWS-managed KMS key that bills per
  # request. Metadata being visible to AWS is an accepted decision (see design.md),
  # and image bytes are client-side encrypted before they ever reach AWS, so a KMS
  # key here would buy nothing but cost.

  deletion_protection_enabled = true

  lifecycle {
    prevent_destroy = true
  }
}
