locals {
  runtime_parameters = {
    database_url        = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${aws_db_instance.main.db_name}?sslmode=require"
    database_username   = aws_db_instance.main.username
    database_secret_arn = aws_db_instance.main.master_user_secret[0].secret_arn
    redis_host          = aws_elasticache_serverless_cache.main.endpoint[0].address
    redis_port          = tostring(aws_elasticache_serverless_cache.main.endpoint[0].port)
    redis_ssl_enabled   = "true"
    allowed_origins     = "https://app.${var.domain_name}"
    api_domain          = local.api_domain
    certificate_arn     = aws_acm_certificate.api.arn
    ecr_repository_url  = aws_ecr_repository.app.repository_url
    artifact_bucket     = aws_s3_bucket.deployment_artifacts.id
  }
}

resource "aws_ssm_parameter" "runtime" {
  for_each = local.runtime_parameters

  name  = "${local.runtime_parameter_path}/config/${replace(each.key, "_", "-")}"
  type  = "String"
  value = each.value
}

