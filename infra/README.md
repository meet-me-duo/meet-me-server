# AWS infrastructure

This directory is split so the remote state and GitHub OIDC trust can be reviewed and applied before production resources.

## Safety boundary

- `bootstrap` creates only the versioned S3 state bucket and the GitHub OIDC role.
- `production` creates billable runtime resources and must not be applied without explicit user approval of the saved plan.
- Real secret values are never Terraform variables. The user creates the `required_secure_parameters` output names directly as SSM `SecureString` values.
- The local operator authenticates with `aws login --profile meet-me-admin`; no long-lived access key is used.
- GitHub trusts only the immutable repository identity and protected `production` Environment subject.

## Validation (no AWS changes)

```powershell
terraform -chdir=infra/bootstrap fmt -check
terraform -chdir=infra/bootstrap init -backend=false
terraform -chdir=infra/bootstrap validate

terraform -chdir=infra/production fmt -check
terraform -chdir=infra/production init -backend=false
terraform -chdir=infra/production validate
```

## Apply gates

1. Review bootstrap plan, then apply it locally with the temporary AWS CLI profile.
2. Register the bootstrap outputs in the protected GitHub `production` Environment.
3. Initialize `production` with the output state bucket using partial backend configuration.
4. Save and review the production plan, resource list, and estimated monthly cost.
5. Apply only after explicit approval.
6. Copy the four `route53_name_servers` values to Gabia only after all existing DNS records have been accounted for.

