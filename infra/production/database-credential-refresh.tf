data "aws_iam_policy_document" "credential_refresh_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "credential_refresh" {
  name               = "${local.name_prefix}-db-credential-refresh"
  assume_role_policy = data.aws_iam_policy_document.credential_refresh_assume_role.json
}

data "aws_iam_policy_document" "credential_refresh" {
  statement {
    effect  = "Allow"
    actions = ["ssm:SendCommand"]
    resources = [
      aws_instance.runtime.arn,
      "arn:${data.aws_partition.current.partition}:ssm:${var.aws_region}::document/AWS-RunShellScript",
      "arn:${data.aws_partition.current.partition}:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:document/AWS-RunShellScript",
    ]
  }
}

resource "aws_iam_role_policy" "credential_refresh" {
  name   = "${local.name_prefix}-db-credential-refresh"
  role   = aws_iam_role.credential_refresh.id
  policy = data.aws_iam_policy_document.credential_refresh.json
}

resource "aws_cloudwatch_event_rule" "database_credential_rotated" {
  name        = "${local.name_prefix}-db-credential-rotated"
  description = "Refresh the app after the RDS master secret becomes current."
  event_pattern = jsonencode({
    source        = ["aws.secretsmanager"]
    "detail-type" = ["Secret Label Updated"]
    resources     = [aws_db_instance.main.master_user_secret[0].secret_arn]
    detail = {
      labelUpdated = ["AWSCURRENT"]
    }
  })
}

resource "aws_cloudwatch_event_rule" "database_credential_reconcile" {
  name                = "${local.name_prefix}-db-credential-reconcile"
  description         = "Recover from a missed rotation event or failed refresh."
  schedule_expression = "rate(5 minutes)"
}

locals {
  credential_refresh_document_arn = "arn:${data.aws_partition.current.partition}:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:document/AWS-RunShellScript"
  credential_refresh_input = jsonencode({
    DocumentName = "AWS-RunShellScript"
    Parameters = {
      commands = ["bash /opt/meet-me/current/scripts/refresh-database-credential.sh"]
    }
  })
}

resource "aws_cloudwatch_event_target" "database_credential_rotated" {
  target_id = "RefreshDatabaseCredential"
  rule      = aws_cloudwatch_event_rule.database_credential_rotated.name
  arn       = local.credential_refresh_document_arn
  role_arn  = aws_iam_role.credential_refresh.arn
  input     = local.credential_refresh_input

  run_command_targets {
    key    = "InstanceIds"
    values = [aws_instance.runtime.id]
  }

  depends_on = [aws_iam_role_policy.credential_refresh]
}

resource "aws_cloudwatch_event_target" "database_credential_reconcile" {
  target_id = "ReconcileDatabaseCredential"
  rule      = aws_cloudwatch_event_rule.database_credential_reconcile.name
  arn       = local.credential_refresh_document_arn
  role_arn  = aws_iam_role.credential_refresh.arn
  input     = local.credential_refresh_input

  run_command_targets {
    key    = "InstanceIds"
    values = [aws_instance.runtime.id]
  }

  depends_on = [aws_iam_role_policy.credential_refresh]
}
