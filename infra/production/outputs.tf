output "route53_name_servers" {
  description = "Public Route 53 name servers the user must enter at Gabia after reviewing the plan."
  value       = aws_route53_zone.main.name_servers
}

output "api_url" {
  description = "Public production API URL after DNS delegation and certificate issuance."
  value       = "https://${local.api_domain}"
}

output "runtime_instance_id" {
  description = "EC2 instance managed through Systems Manager; no SSH key is created."
  value       = aws_instance.runtime.id
}

output "runtime_public_ip" {
  description = "Elastic IPv4 address used by the API A record."
  value       = aws_eip.runtime.public_ip
}

output "ecr_repository_url" {
  description = "GitHub production Environment variable ECR_REPOSITORY_URL."
  value       = aws_ecr_repository.app.repository_url
}

output "deployment_artifact_bucket" {
  description = "GitHub production Environment variable DEPLOYMENT_ARTIFACT_BUCKET."
  value       = aws_s3_bucket.deployment_artifacts.id
}

output "api_certificate_arn" {
  description = "Exportable ACM certificate ARN. It remains pending until Route 53 delegation is effective."
  value       = aws_acm_certificate.api.arn
}

output "required_secure_parameters" {
  description = "Parameter names the user must create as SecureString values. Values must never enter Terraform state."
  value = {
    gemini_api_key        = "${local.runtime_parameter_path}/secret/gemini-api-key"
    acm_export_passphrase = "${local.runtime_parameter_path}/secret/acm-export-passphrase"
  }
}

