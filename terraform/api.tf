# One HTTP API, one Lambda with an internal router (src/lambda/api/router.ts) —
# simpler IAM, one bundle, fewer cold starts than a Lambda per route. JWT-authorised
# against the Cognito pool from cognito.tf, except /health.

data "archive_file" "api_lambda" {
  type        = "zip"
  source_dir  = "${path.module}/../dist/api"
  output_path = "${path.module}/.build/api.zip"
}

resource "aws_lambda_function" "api" {
  function_name = "${local.name_prefix}-api"
  role          = aws_iam_role.api_lambda.arn

  filename         = data.archive_file.api_lambda.output_path
  source_code_hash = data.archive_file.api_lambda.output_base64sha256

  handler = "index.handler"
  runtime = "nodejs22.x"
  timeout = 15

  environment {
    variables = {
      MEDIA_TABLE      = aws_dynamodb_table.media.name
      ORIGINALS_BUCKET = aws_s3_bucket.originals.bucket
      DERIVED_BUCKET   = aws_s3_bucket.derived.bucket
      USER_POOL_ID     = aws_cognito_user_pool.users.id
    }
  }

  depends_on = [aws_cloudwatch_log_group.api_lambda]
}

resource "aws_lambda_permission" "api_invoke" {
  statement_id  = "AllowApiGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.api.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.http.execution_arn}/*/*"
}

resource "aws_apigatewayv2_api" "http" {
  name          = "${local.name_prefix}-api"
  protocol_type = "HTTP"

  cors_configuration {
    allow_origins = local.web_origins
    allow_methods = ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]
    allow_headers = ["authorization", "content-type"]
    max_age       = 300
  }
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.http.id
  name        = "$default"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_gateway.arn
    format = jsonencode({
      requestId      = "$context.requestId"
      routeKey       = "$context.routeKey"
      status         = "$context.status"
      integrationErr = "$context.integrationErrorMessage"
      responseLength = "$context.responseLength"
    })
  }
}

resource "aws_cloudwatch_log_group" "api_gateway" {
  name              = "/aws/apigateway/${local.name_prefix}-api"
  retention_in_days = 30
}

resource "aws_apigatewayv2_integration" "api" {
  api_id                 = aws_apigatewayv2_api.http.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.api.invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id           = aws_apigatewayv2_api.http.id
  name             = "${local.name_prefix}-jwt"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    audience = [aws_cognito_user_pool_client.app.id]
    issuer   = "https://cognito-idp.${var.aws_region}.amazonaws.com/${aws_cognito_user_pool.users.id}"
  }
}

# Every route not named here explicitly is a 404 by construction — HTTP API has
# no catch-all fallback, unlike REST API's greedy proxy. Routes are added here as
# each plan step introduces the endpoint it needs; the handler behind all of them
# is the one Lambda above, routed internally by router.ts.
locals {
  # method, route path. Every one but health requires the JWT authorizer.
  authorized_routes = [
    "POST /session/bootstrap",
    "GET /keys",
    "POST /keys",
    "DELETE /keys/{wrapId}",
    "POST /keys/version",
    "GET /keys/hash-secret",
    "PUT /keys/hash-secret",
    "POST /uploads",
    "GET /photos",
    "GET /photos/{photoId}",
    "PATCH /photos/{photoId}/renditions/{renditionId}",
    "DELETE /photos/{photoId}",
    "DELETE /photos/{photoId}/renditions/{renditionId}",
    "POST /photos/{photoId}/restore",
    "GET /trash",
    "GET /facets",
    "GET /facets/{type}/{value}",
    "DELETE /account",
  ]
}

resource "aws_apigatewayv2_route" "health" {
  api_id    = aws_apigatewayv2_api.http.id
  route_key = "GET /health"
  target    = "integrations/${aws_apigatewayv2_integration.api.id}"
}

resource "aws_apigatewayv2_route" "authorized" {
  for_each = toset(local.authorized_routes)

  api_id             = aws_apigatewayv2_api.http.id
  route_key          = each.value
  target             = "integrations/${aws_apigatewayv2_integration.api.id}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id
}
