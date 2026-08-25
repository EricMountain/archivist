# The instance's domain: certificate, distribution, and the behaviours that route
# /api/*, /media/* and /thumbs/* to their backends while everything else falls
# through to the placeholder web app. See plan step 1.14.

# CloudFront requires its certificate in us-east-1 regardless of where the rest
# of the stack lives.
provider "aws" {
  alias   = "us_east_1"
  region  = "us-east-1"
  profile = var.aws_profile

  default_tags {
    tags = local.tags
  }
}

resource "aws_acm_certificate" "cdn" {
  provider          = aws.us_east_1
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_acm_certificate_validation" "cdn" {
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.cdn.arn
  validation_record_fqdns = [for r in aws_route53_record.cert_validation : r.fqdn]
}

# One OAC, reused across all three S3 origins — CloudFront allows this, and a
# private bucket only trusts requests signed for this specific distribution (via
# the bucket policies below), regardless of how many origins share it.
resource "aws_cloudfront_origin_access_control" "s3" {
  name                              = "${local.name_prefix}-s3-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# Forwards everything but the Host header — needed so the JWT Authorization
# header and query strings reach API Gateway unmodified.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

# S3 keys use the design's own prefixes (raw/, th/ — see design.md), not the
# public URL prefixes (/media/, /thumbs/). These strip the CloudFront-only prefix
# at the edge before the request reaches the origin, so the public URL shape
# stays decoupled from S3 layout, same as everything else about renames and
# bucket moves in this design.
resource "aws_cloudfront_function" "strip_media_prefix" {
  name    = "${local.name_prefix}-strip-media-prefix"
  runtime = "cloudfront-js-1.0"
  publish = true
  code    = <<-JS
    function handler(event) {
      var request = event.request;
      request.uri = request.uri.replace(/^\/media/, '') || '/';
      return request;
    }
  JS
}

resource "aws_cloudfront_function" "strip_api_prefix" {
  name    = "${local.name_prefix}-strip-api-prefix"
  runtime = "cloudfront-js-1.0"
  publish = true
  code    = <<-JS
    function handler(event) {
      var request = event.request;
      request.uri = request.uri.replace(/^\/api/, '') || '/';
      return request;
    }
  JS
}

resource "aws_cloudfront_function" "strip_thumbs_prefix" {
  name    = "${local.name_prefix}-strip-thumbs-prefix"
  runtime = "cloudfront-js-1.0"
  publish = true
  code    = <<-JS
    function handler(event) {
      var request = event.request;
      request.uri = request.uri.replace(/^\/thumbs/, '') || '/';
      return request;
    }
  JS
}

# Thumbnail keys are ULID-derived and therefore immutable — cacheable for a year
# at the edge. See "Cache policy" in plan step 1.14.
resource "aws_cloudfront_cache_policy" "thumbnails_immutable" {
  name        = "${local.name_prefix}-thumbnails-immutable"
  default_ttl = 31536000
  max_ttl     = 31536000
  min_ttl     = 0

  parameters_in_cache_key_and_forwarded_to_origin {
    cookies_config {
      cookie_behavior = "none"
    }
    headers_config {
      header_behavior = "none"
    }
    query_strings_config {
      query_string_behavior = "none"
    }
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true
  }
}

resource "aws_cloudfront_distribution" "cdn" {
  enabled             = true
  is_ipv6_enabled     = true
  default_root_object = "index.html"
  aliases             = [var.domain_name]

  origin {
    origin_id                = "web"
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id                = "originals"
    domain_name              = aws_s3_bucket.originals.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id                = "derived"
    domain_name              = aws_s3_bucket.derived.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id   = "api"
    domain_name = trimsuffix(trimprefix(aws_apigatewayv2_api.http.api_endpoint, "https://"), "/")

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id       = "web"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
  }

  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = "api"
    viewer_protocol_policy   = "https-only"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.strip_api_prefix.arn
    }
  }

  # Originals are large and rarely re-fetched — not cached at the edge. See "S3
  # layout and storage tiering" in design.md.
  ordered_cache_behavior {
    path_pattern           = "/media/*"
    target_origin_id       = "originals"
    viewer_protocol_policy = "https-only"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_disabled.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.strip_media_prefix.arn
    }
  }

  ordered_cache_behavior {
    path_pattern           = "/thumbs/*"
    target_origin_id       = "derived"
    viewer_protocol_policy = "https-only"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = aws_cloudfront_cache_policy.thumbnails_immutable.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.strip_thumbs_prefix.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cdn.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# ---------------------------------------------------------------------------
# Bucket policies: only this specific distribution, via OAC, may read. Buckets
# stay private — public access blocks remain on (s3.tf, wellknown.tf).
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "web_oac" {
  statement {
    sid       = "AllowCloudFrontOAC"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.web.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.cdn.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.id
  policy = data.aws_iam_policy_document.web_oac.json
}

data "aws_iam_policy_document" "originals_oac" {
  statement {
    sid       = "AllowCloudFrontOAC"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.originals.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.cdn.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "originals" {
  bucket = aws_s3_bucket.originals.id
  policy = data.aws_iam_policy_document.originals_oac.json
}

data "aws_iam_policy_document" "derived_oac" {
  statement {
    sid       = "AllowCloudFrontOAC"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.derived.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.cdn.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "derived" {
  bucket = aws_s3_bucket.derived.id
  policy = data.aws_iam_policy_document.derived_oac.json
}
