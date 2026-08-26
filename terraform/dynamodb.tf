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
    name = "timelinePk"
    type = "S"
  }

  attribute {
    name = "timelineSk"
    type = "S"
  }

  attribute {
    name = "facetPk"
    type = "S"
  }

  attribute {
    name = "facetSk"
    type = "S"
  }

  # Timeline. Sparse: only #META items carry timelinePk/timelineSk, so renditions,
  # facets and pointers are structurally incapable of appearing in it.
  global_secondary_index {
    name               = "timeline_gsi"
    projection_type    = "INCLUDE"
    non_key_attributes = local.grid_projection

    key_schema {
      attribute_name = "timelinePk"
      key_type       = "HASH"
    }

    key_schema {
      attribute_name = "timelineSk"
      key_type       = "RANGE"
    }
  }

  # Facets: labels, camera, device, rendition roles. Sparse over F# items.
  global_secondary_index {
    name               = "facet_gsi"
    projection_type    = "INCLUDE"
    non_key_attributes = local.grid_projection

    key_schema {
      attribute_name = "facetPk"
      key_type       = "HASH"
    }

    key_schema {
      attribute_name = "facetSk"
      key_type       = "RANGE"
    }
  }

  point_in_time_recovery {
    enabled = true
  }

  # Purge tombstones only — see "Purge tombstones" in design.md. Nothing else in
  # this table carries expiresAt, so nothing else is at risk of TTL expiring it.
  # The asset sweep itself is NOT TTL-driven (that would orphan S3 objects); this
  # is exclusively for the HASH-pointer tombstones the sweep leaves behind.
  ttl {
    attribute_name = "expiresAt"
    enabled        = true
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
