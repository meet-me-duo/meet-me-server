variable "aws_region" {
  description = "AWS region used by the production environment."
  type        = string
  default     = "ap-northeast-2"
}

variable "github_owner" {
  description = "GitHub organization name."
  type        = string
  default     = "meet-me-duo"
}

variable "github_repository" {
  description = "GitHub repository name."
  type        = string
  default     = "meet-me-server"
}

variable "github_owner_id" {
  description = "Immutable numeric GitHub organization ID used in OIDC subject claims."
  type        = number
  default     = 319248346
}

variable "github_repository_id" {
  description = "Immutable numeric GitHub repository ID used in OIDC subject claims."
  type        = number
  default     = 1341255198
}

variable "github_environment" {
  description = "Protected GitHub Environment allowed to assume the AWS role."
  type        = string
  default     = "production"
}
