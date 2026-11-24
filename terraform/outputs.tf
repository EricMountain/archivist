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

output "web_bucket" {
  description = "Public web origin: the discovery document, and region packs under geo/ (make geo-publish)."
  value       = aws_s3_bucket.web.bucket
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

output "ci_deploy_role_arn" {
  description = "Role GitHub Actions assumes to deploy Lambda code (the AWS_DEPLOY_ROLE_ARN environment secret). Null when github_repository is unset."
  value       = local.ci_enabled ? aws_iam_role.ci_deploy[0].arn : null
}

output "deployed_commit_parameter" {
  description = "SSM parameter naming the commit that's live; make deploy writes it after an apply. Null when github_repository is unset."
  value       = local.ci_enabled ? aws_ssm_parameter.deployed_commit[0].name : null
}
