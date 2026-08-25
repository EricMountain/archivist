terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
  }
}

provider "aws" {
  region = var.aws_region

  # Normally null: credentials come from the environment (AWS_PROFILE or an assumed
  # role), so no operator's profile name is written into the configuration.
  profile = var.aws_profile

  default_tags {
    tags = local.tags
  }
}
