output "media_table_name" {
  description = "DynamoDB table holding all metadata."
  value       = aws_dynamodb_table.media.name
}

output "media_table_arn" {
  value = aws_dynamodb_table.media.arn
}

output "originals_bucket" {
  description = "Client-side encrypted originals."
  value       = aws_s3_bucket.originals.bucket
}

output "derived_bucket" {
  description = "Client-side encrypted thumbnails."
  value       = aws_s3_bucket.derived.bucket
}

output "region" {
  value = var.aws_region
}

output "cognito_user_pool_id" {
  value = aws_cognito_user_pool.users.id
}

output "cognito_user_pool_client_id" {
  value = aws_cognito_user_pool_client.app.id
}

output "api_base_url" {
  description = "Invoke URL of the HTTP API's default stage."
  value       = aws_apigatewayv2_stage.default.invoke_url
}
