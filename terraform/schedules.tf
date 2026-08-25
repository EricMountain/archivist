# Daily purge sweep. Not DynamoDB TTL — see "Purging" in design.md for why an
# explicit scheduled job is the right shape here.

data "archive_file" "purge_lambda" {
  type        = "zip"
  source_dir  = "${path.module}/../dist/purge"
  output_path = "${path.module}/.build/purge.zip"
}

resource "aws_lambda_function" "purge" {
  function_name = "${local.name_prefix}-purge"
  role          = aws_iam_role.purge_lambda.arn

  filename         = data.archive_file.purge_lambda.output_path
  source_code_hash = data.archive_file.purge_lambda.output_base64sha256

  handler = "index.handler"
  runtime = "nodejs22.x"
  # Generous timeout: a purge run walks every owner's trash and issues batched
  # S3/DynamoDB deletes. Idempotent, so a timeout just means tomorrow's run picks
  # up whatever's left — see repo/purge.ts.
  timeout = 900

  environment {
    variables = {
      MEDIA_TABLE = aws_dynamodb_table.media.name
    }
  }

  depends_on = [aws_cloudwatch_log_group.purge_lambda]
}

resource "aws_cloudwatch_event_rule" "purge_daily" {
  name                = "${local.name_prefix}-purge-daily"
  description         = "Daily trash purge sweep"
  schedule_expression = "rate(1 day)"
}

resource "aws_cloudwatch_event_target" "purge_daily" {
  rule = aws_cloudwatch_event_rule.purge_daily.name
  arn  = aws_lambda_function.purge.arn
}

resource "aws_lambda_permission" "purge_eventbridge" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.purge.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.purge_daily.arn
}
