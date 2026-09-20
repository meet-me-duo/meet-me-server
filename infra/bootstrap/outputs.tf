output "terraform_state_bucket" {
  description = "S3 bucket used by the production Terraform backend."
  value       = aws_s3_bucket.terraform_state.id
}

output "github_deploy_role_arn" {
  description = "GitHub production Environment variable AWS_DEPLOY_ROLE_ARN."
  value       = aws_iam_role.github_deploy.arn
}

output "github_oidc_subject" {
  description = "Exact immutable GitHub OIDC subject trusted by AWS."
  value       = local.oidc_subject
}

