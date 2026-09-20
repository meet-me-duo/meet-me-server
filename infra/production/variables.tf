variable "aws_region" {
  description = "AWS region for all regional production resources."
  type        = string
  default     = "ap-northeast-2"
}

variable "project_name" {
  description = "Resource name prefix."
  type        = string
  default     = "meet-me"
}

variable "domain_name" {
  description = "Public service domain delegated to Route 53."
  type        = string
  default     = "meet-me.co.kr"
}

variable "instance_type" {
  description = "Graviton EC2 instance type for the MVP runtime."
  type        = string
  default     = "t4g.small"
}

variable "db_instance_class" {
  description = "RDS instance class for the MVP database."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_engine_version" {
  description = "RDS PostgreSQL major version."
  type        = string
  default     = "18"
}

variable "db_deletion_protection" {
  description = "Protect the production RDS instance from accidental deletion."
  type        = bool
  default     = true
}

variable "ec2_compose_version" {
  description = "Pinned Docker Compose plugin version installed on EC2."
  type        = string
  default     = "v2.40.3"
}

variable "github_deploy_role_name" {
  description = "Bootstrap-created GitHub OIDC role receiving production deploy permissions."
  type        = string
  default     = "meet-me-github-production"
}
