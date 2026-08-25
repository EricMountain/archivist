# This configuration is deployed by each operator into their own AWS account. Nothing
# here may name a specific person's domain, account or region — see
# docs/design/deployment.md.

variable "domain_name" {
  description = "Domain this instance is served from, e.g. photos.example.com. No default: every deployment has its own."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9.-]+\\.[a-z]{2,}$", var.domain_name))
    error_message = "Expected a bare hostname without scheme or trailing slash."
  }
}

variable "aws_region" {
  description = "Region for all resources. Operators should pick one near themselves; see README for the trade-offs."
  type        = string
  default     = "eu-west-1"
}

variable "aws_profile" {
  description = "Named AWS CLI profile to use. Leave null to use the standard credential chain — normally you want AWS_PROFILE in the environment instead, so no profile name ends up in a file."
  type        = string
  default     = null
}

variable "app_name" {
  description = "Prefix for every resource name. Change only if it collides with something you already run."
  type        = string
  default     = "archivist"
}

variable "environment" {
  description = "Environment name. 'prod' is unsuffixed; anything else is inserted into resource names."
  type        = string
  default     = "prod"

  validation {
    condition     = can(regex("^[a-z0-9]+$", var.environment))
    error_message = "Environment must be lowercase alphanumeric — it becomes part of resource names."
  }
}

variable "web_origins" {
  description = "Extra origins allowed to PUT/GET S3 objects from a browser. The instance's own domain is always included; set this only for local development."
  type        = list(string)
  default     = []
}

variable "noncurrent_version_retention_days" {
  description = "How long superseded object versions survive in the originals bucket."
  type        = number
  default     = 30
}

variable "enable_google_idp" {
  description = "Federate Cognito sign-in with Google in addition to passkeys. An operator may not want a Google dependency, so this defaults off."
  type        = bool
  default     = false
}

variable "google_client_id" {
  description = "OAuth client ID from the Google Cloud console. Required only when enable_google_idp is true."
  type        = string
  default     = null
}

variable "instance_name" {
  description = "Human-readable name for this instance, shown in the app and served in the discovery document."
  type        = string
  default     = "Archivist"
}

variable "route53_zone_name" {
  description = "Route 53 hosted zone to create DNS records in. Defaults to domain_name with its first label stripped (e.g. \"example.com\" for \"photos.example.com\"); override if that guess is wrong, such as for an apex domain."
  type        = string
  default     = null
}

variable "google_client_secret" {
  description = "OAuth client secret from the Google Cloud console. Required only when enable_google_idp is true. Set via a *.auto.tfvars file in private/, never committed."
  type        = string
  default     = null
  sensitive   = true
}
