# The CI deploy role (plan 03, step 3.11): lets GitHub Actions, running in this
# instance's GitHub environment of one repository, update this instance's Lambda code
# and record which commit is live. Nothing else — no `terraform apply` from CI (plan 03,
# D3), so infrastructure changes stay with `make deploy`.
#
# Opt-in: with var.github_repository unset (the default) every resource here has
# count = 0, and a fork or another operator's clone gets nothing.
#
# The GitHub OIDC identity provider is looked up, not created: it's one per account,
# shared by every workspace, and a second workspace would try to create it again. It's
# created by hand once per account (plan 03, step 3.3).

locals {
  ci_enabled = var.github_repository != null

  # The part of GitHub's OIDC `sub` claim before ":environment:…". Repositories on
  # GitHub's immutable subject format (the default for repositories that have it
  # enabled; check with `gh api repos/OWNER/REPO/actions/oidc/customization/sub`) send
  # "repo:owner@<owner id>/repo@<repo id>", which a recreated or renamed-into repository
  # can't reproduce; others send plain "repo:owner/repo".
  ci_subject_prefix = coalesce(var.github_oidc_subject_prefix, "repo:${coalesce(var.github_repository, "-")}")
}

data "aws_iam_openid_connect_provider" "github" {
  count = local.ci_enabled ? 1 : 0
  url   = "https://token.actions.githubusercontent.com"
}

data "aws_iam_policy_document" "ci_deploy_assume" {
  count = local.ci_enabled ? 1 : 0

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github[0].arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Match the GitHub environment, not the branch: the environment is what enforces
    # "deploy from main only" (plan 03, step 3.4), and a job outside it — any branch,
    # any PR, any other workflow — gets a different `sub` and is denied. The default
    # environment name "prod" is also the GitHub environment's name.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${local.ci_subject_prefix}:environment:${var.environment}"]
    }
  }
}

resource "aws_iam_role" "ci_deploy" {
  count                = local.ci_enabled ? 1 : 0
  name                 = "${local.name_prefix}-ci-deploy"
  assume_role_policy   = data.aws_iam_policy_document.ci_deploy_assume[0].json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "ci_deploy" {
  count = local.ci_enabled ? 1 : 0

  # Named functions, no wildcard: a fourth Lambda has to be granted on purpose.
  statement {
    sid = "UpdateLambdaCode"
    actions = [
      "lambda:UpdateFunctionCode",
      "lambda:GetFunction",
      "lambda:GetFunctionConfiguration",
    ]
    resources = [
      aws_lambda_function.api.arn,
      aws_lambda_function.s3event.arn,
      aws_lambda_function.purge.arn,
    ]
  }

  statement {
    sid = "DeployedCommitMarker"
    actions = [
      "ssm:GetParameter",
      "ssm:PutParameter",
    ]
    resources = [aws_ssm_parameter.deployed_commit[0].arn]
  }
}

resource "aws_iam_role_policy" "ci_deploy" {
  count  = local.ci_enabled ? 1 : 0
  name   = "${local.name_prefix}-ci-deploy"
  role   = aws_iam_role.ci_deploy[0].id
  policy = data.aws_iam_policy_document.ci_deploy[0].json
}

# The commit that's live on this instance: a full SHA, `<sha>-dirty` after a deploy
# from a dirty tree, or `unknown` until the first deploy. Terraform owns that it exists;
# `make deploy` (step 3.12) and the CI deploy (step 3.21) own what it says, hence
# ignore_changes. CI refuses to deploy from `unknown` or `*-dirty`.
resource "aws_ssm_parameter" "deployed_commit" {
  count       = local.ci_enabled ? 1 : 0
  name        = "/${local.name_prefix}/deployed-commit"
  description = "Commit deployed to this instance; written by make deploy and the CI deploy."
  type        = "String"
  value       = "unknown"

  lifecycle {
    ignore_changes = [value]
  }
}
