# Per-instance authentication. Passkey-first (WebAuthn), MFA off — the passkey is
# the factor, not a second one. See "Authentication" in docs/design/design.md.
#
# Cognito proves *who you are* to the API; it never touches key custody or the
# master key. That's a separate, app-controlled WebAuthn PRF ceremony — see "Auth
# and key custody stay separate" in design.md. Don't conflate the two here.

resource "aws_cognito_user_pool" "users" {
  name = "${local.name_prefix}-users"

  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  mfa_configuration        = "OFF"

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  # Invite-only, per deployment.md — not a capacity limit, a deliberate choice
  # about who the operator is a data controller for. Self-service sign-up would
  # defeat it: the discovery document publishes this pool's client ID and pool ID
  # unauthenticated (by design — see wellknown.tf), so anyone who finds the
  # domain could otherwise call Cognito's public SignUp API directly and mint
  # themselves a fully-isolated library via POST /session/bootstrap. The operator
  # invites people with `aws cognito-idp admin-create-user`.
  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  password_policy {
    minimum_length    = 12
    require_lowercase = true
    require_numbers   = true
    require_symbols   = true
    require_uppercase = true
  }

  # Passkey sign-in via the unified USER_AUTH flow (see the app client below).
  # PASSWORD stays allowed as a first factor too — Cognito user records always
  # carry one, and it's the recovery path if WebAuthn is unavailable in a browser.
  sign_in_policy {
    allowed_first_auth_factors = ["PASSWORD", "WEB_AUTHN"]
  }

  # The relying party ID must be this instance's own domain — WebAuthn credentials
  # are scoped to it and won't work against any other origin.
  web_authn_configuration {
    relying_party_id  = var.domain_name
    user_verification = "preferred"
  }

  deletion_protection = "ACTIVE"

  lifecycle {
    prevent_destroy = true
  }
}

# No client secret: this is a public client (mobile app, browser), and a secret
# embedded in either isn't one. Refresh token 30 days, access token 1 hour — see
# plan step 1.4.
resource "aws_cognito_user_pool_client" "app" {
  name         = "${local.name_prefix}-app"
  user_pool_id = aws_cognito_user_pool.users.id

  generate_secret = false

  explicit_auth_flows = [
    "ALLOW_USER_AUTH",     # unified flow: carries WEB_AUTHN and PASSWORD first factors
    "ALLOW_USER_SRP_AUTH", # password fallback outside the unified flow
    "ALLOW_REFRESH_TOKEN_AUTH",
  ]

  access_token_validity  = 1
  id_token_validity      = 1
  refresh_token_validity = 30
  # Defaults for access/id are hours and for refresh is days; stated for clarity
  # rather than left to guesswork.
  token_validity_units {
    access_token  = "hours"
    id_token      = "hours"
    refresh_token = "days"
  }

  prevent_user_existence_errors = "ENABLED"

  supported_identity_providers = concat(
    ["COGNITO"],
    var.enable_google_idp ? [aws_cognito_identity_provider.google[0].provider_name] : [],
  )

  # OAuth redirect settings are only meaningful once there's a federated IdP to
  # redirect through — Cognito's native WebAuthn/password flows never use them.
  allowed_oauth_flows_user_pool_client = var.enable_google_idp
  allowed_oauth_flows                  = var.enable_google_idp ? ["code"] : []
  allowed_oauth_scopes                 = var.enable_google_idp ? ["openid", "email", "profile"] : []
  callback_urls                        = var.enable_google_idp ? ["https://${var.domain_name}/auth/callback"] : []
  logout_urls                          = var.enable_google_idp ? ["https://${var.domain_name}/auth/logout"] : []
}

# Only provisioned when Google federation is enabled, and only then does the
# Hosted UI domain below have anything to redirect through.
resource "aws_cognito_identity_provider" "google" {
  count = var.enable_google_idp ? 1 : 0

  user_pool_id  = aws_cognito_user_pool.users.id
  provider_name = "Google"
  provider_type = "Google"

  provider_details = {
    client_id        = var.google_client_id
    client_secret    = var.google_client_secret
    authorize_scopes = "openid email profile"
  }

  attribute_mapping = {
    email    = "email"
    username = "sub"
  }
}

# Cognito's own OAuth domain, for the redirect Google federation needs. A
# Cognito-prefix domain, not the instance's own domain — the instance's domain and
# certificate are CloudFront's job (step 1.14), and Google sign-in is a redirect
# through Cognito, not through the app's own origin.
resource "aws_cognito_user_pool_domain" "hosted_ui" {
  count = var.enable_google_idp ? 1 : 0

  domain       = "${local.name_prefix}-${local.bucket_suffix}"
  user_pool_id = aws_cognito_user_pool.users.id
}
