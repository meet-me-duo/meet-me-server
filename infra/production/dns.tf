resource "aws_route53_zone" "main" {
  name = var.domain_name
}

resource "aws_eip" "runtime" {
  domain = "vpc"

  tags = { Name = local.name_prefix }
}

resource "aws_route53_record" "api_ipv4" {
  zone_id = aws_route53_zone.main.zone_id
  name    = local.api_domain
  type    = "A"
  ttl     = 60
  records = [aws_eip.runtime.public_ip]
}

resource "aws_acm_certificate" "api" {
  domain_name       = local.api_domain
  validation_method = "DNS"
  key_algorithm     = "RSA_2048"

  options {
    certificate_transparency_logging_preference = "ENABLED"
    export                                      = "ENABLED"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "api_certificate_validation" {
  for_each = {
    for option in aws_acm_certificate.api.domain_validation_options : option.domain_name => {
      name   = option.resource_record_name
      record = option.resource_record_value
      type   = option.resource_record_type
    }
  }

  allow_overwrite = true
  zone_id         = aws_route53_zone.main.zone_id
  name            = each.value.name
  type            = each.value.type
  ttl             = 60
  records         = [each.value.record]
}

