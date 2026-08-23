# Chicken-and-egg: the S3 backend needs a bucket that Terraform hasn't created yet.
# This root module runs once with local state, creates the state bucket, and is then
# left alone. Its own state file is committed nowhere and matters to nobody — if it
# is lost, import the bucket or leave it be.
#
#   cd terraform/bootstrap
#   terraform init && terraform apply

terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region  = var.aws_region
  profile = var.aws_profile
}

variable "aws_region" {
  type    = string
  default = "eu-west-1"
}

variable "aws_profile" {
  description = "Leave null and set AWS_PROFILE in the environment instead."
  type        = string
  default     = null
}

variable "app_name" {
  type    = string
  default = "archivist"
}

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "tfstate" {
  bucket = "${var.app_name}-tfstate-${data.aws_caller_identity.current.account_id}"

  lifecycle {
    prevent_destroy = true
  }

  tags = {
    Application = var.app_name
    ManagedBy   = "terraform"
    Purpose     = "terraform-state"
  }
}

# Non-negotiable for state: versioning is the only recovery path from a corrupt or
# truncated apply.
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket                  = aws_s3_bucket.tfstate.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }

  depends_on = [aws_s3_bucket_versioning.tfstate]
}

output "state_bucket" {
  value = aws_s3_bucket.tfstate.bucket
}
