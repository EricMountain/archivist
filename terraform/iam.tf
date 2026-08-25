# One execution role per Lambda, each scoped to exactly the resources that
# function touches. No wildcards on resources — see plan step 1.5.

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

# ---------------------------------------------------------------------------
# API Lambda: the table and its two indexes, plus s3:PutObject on both media
# buckets — that's what presigning an upload URL requires, since a presigned
# request is authorised against the credentials that signed it.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "api_lambda" {
  name              = "/aws/lambda/${local.name_prefix}-api"
  retention_in_days = 30
}

resource "aws_iam_role" "api_lambda" {
  name               = "${local.name_prefix}-api-lambda"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "api_lambda" {
  statement {
    sid = "Logs"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = ["${aws_cloudwatch_log_group.api_lambda.arn}:*"]
  }

  statement {
    sid = "MediaTable"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:Query",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:DeleteItem",
      "dynamodb:BatchWriteItem",
      "dynamodb:TransactWriteItems",
      "dynamodb:TransactGetItems",
    ]
    resources = [
      aws_dynamodb_table.media.arn,
      "${aws_dynamodb_table.media.arn}/index/*",
    ]
  }

  statement {
    sid     = "PresignUploads"
    actions = ["s3:PutObject"]
    resources = [
      "${aws_s3_bucket.originals.arn}/*",
      "${aws_s3_bucket.derived.arn}/*",
    ]
  }

  # DELETE /account (plan step 1.15): removes every S3 object under the owner's
  # prefix, and the Cognito user record itself.
  statement {
    sid     = "AccountDeletionS3"
    actions = ["s3:DeleteObject"]
    resources = [
      "${aws_s3_bucket.originals.arn}/*",
      "${aws_s3_bucket.derived.arn}/*",
    ]
  }

  statement {
    sid       = "AccountDeletionCognito"
    actions   = ["cognito-idp:AdminDeleteUser"]
    resources = [aws_cognito_user_pool.users.arn]
  }
}

resource "aws_iam_role_policy" "api_lambda" {
  name   = "${local.name_prefix}-api-lambda"
  role   = aws_iam_role.api_lambda.id
  policy = data.aws_iam_policy_document.api_lambda.json
}

# ---------------------------------------------------------------------------
# S3-event Lambda (step 1.10): the table and its indexes, plus read-only S3 to
# confirm an object's existence and size. It never opens a file.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "s3event_lambda" {
  name              = "/aws/lambda/${local.name_prefix}-s3event"
  retention_in_days = 30
}

resource "aws_iam_role" "s3event_lambda" {
  name               = "${local.name_prefix}-s3event-lambda"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "s3event_lambda" {
  statement {
    sid = "Logs"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = ["${aws_cloudwatch_log_group.s3event_lambda.arn}:*"]
  }

  statement {
    sid = "MediaTable"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:Query",
      "dynamodb:UpdateItem",
    ]
    resources = [
      aws_dynamodb_table.media.arn,
      "${aws_dynamodb_table.media.arn}/index/*",
    ]
  }

  statement {
    sid     = "ConfirmArrival"
    actions = ["s3:GetObject"]
    resources = [
      "${aws_s3_bucket.originals.arn}/*",
      "${aws_s3_bucket.derived.arn}/*",
    ]
  }
}

resource "aws_iam_role_policy" "s3event_lambda" {
  name   = "${local.name_prefix}-s3event-lambda"
  role   = aws_iam_role.s3event_lambda.id
  policy = data.aws_iam_policy_document.s3event_lambda.json
}

# ---------------------------------------------------------------------------
# Purge Lambda (step 1.13): full read/write on the table (it deletes items and
# converts HASH pointers to tombstones) plus s3:DeleteObject on both buckets.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "purge_lambda" {
  name              = "/aws/lambda/${local.name_prefix}-purge"
  retention_in_days = 30
}

resource "aws_iam_role" "purge_lambda" {
  name               = "${local.name_prefix}-purge-lambda"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "purge_lambda" {
  statement {
    sid = "Logs"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = ["${aws_cloudwatch_log_group.purge_lambda.arn}:*"]
  }

  statement {
    sid = "MediaTable"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:Query",
      "dynamodb:UpdateItem",
      "dynamodb:DeleteItem",
      "dynamodb:BatchWriteItem",
    ]
    resources = [
      aws_dynamodb_table.media.arn,
      "${aws_dynamodb_table.media.arn}/index/*",
    ]
  }

  statement {
    sid     = "PurgeObjects"
    actions = ["s3:DeleteObject"]
    resources = [
      "${aws_s3_bucket.originals.arn}/*",
      "${aws_s3_bucket.derived.arn}/*",
    ]
  }
}

resource "aws_iam_role_policy" "purge_lambda" {
  name   = "${local.name_prefix}-purge-lambda"
  role   = aws_iam_role.purge_lambda.id
  policy = data.aws_iam_policy_document.purge_lambda.json
}
