# S3 ObjectCreated on both media buckets, confirming arrival and flipping an
# asset's status. See plan step 1.10 and src/lambda/s3event/index.ts.

data "archive_file" "s3event_lambda" {
  type        = "zip"
  source_dir  = "${path.module}/../dist/s3event"
  output_path = "${path.module}/.build/s3event.zip"
}

resource "aws_lambda_function" "s3event" {
  function_name = "${local.name_prefix}-s3event"
  role          = aws_iam_role.s3event_lambda.arn

  filename         = data.archive_file.s3event_lambda.output_path
  source_code_hash = data.archive_file.s3event_lambda.output_base64sha256

  handler = "index.handler"
  runtime = "nodejs22.x"
  timeout = 30

  environment {
    variables = {
      MEDIA_TABLE = aws_dynamodb_table.media.name
    }
  }

  depends_on = [aws_cloudwatch_log_group.s3event_lambda]
}

resource "aws_lambda_permission" "s3event_originals" {
  statement_id  = "AllowOriginalsBucketInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.s3event.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = aws_s3_bucket.originals.arn
}

resource "aws_lambda_permission" "s3event_derived" {
  statement_id  = "AllowDerivedBucketInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.s3event.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = aws_s3_bucket.derived.arn
}

resource "aws_s3_bucket_notification" "originals" {
  bucket = aws_s3_bucket.originals.id

  lambda_function {
    lambda_function_arn = aws_lambda_function.s3event.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = "raw/"
  }

  depends_on = [aws_lambda_permission.s3event_originals]
}

resource "aws_s3_bucket_notification" "derived" {
  bucket = aws_s3_bucket.derived.id

  lambda_function {
    lambda_function_arn = aws_lambda_function.s3event.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = "th/"
  }

  depends_on = [aws_lambda_permission.s3event_derived]
}
