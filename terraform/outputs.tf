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
