resource "aws_db_subnet_group" "main" {
  name       = local.name_prefix
  subnet_ids = values(aws_subnet.private)[*].id

  tags = { Name = local.name_prefix }
}

resource "aws_db_instance" "main" {
  identifier = local.name_prefix

  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  db_name                     = "meetme"
  username                    = "meetme_admin"
  manage_master_user_password = true
  port                        = 5432

  allocated_storage     = 20
  max_allocated_storage = 100
  storage_type          = "gp3"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.database.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period = 7
  backup_window           = "18:00-19:00"
  maintenance_window      = "sun:19:00-sun:20:00"

  auto_minor_version_upgrade = true
  deletion_protection        = var.db_deletion_protection
  skip_final_snapshot        = false
  final_snapshot_identifier  = "${local.name_prefix}-final"
  copy_tags_to_snapshot      = true

  performance_insights_enabled = false

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_elasticache_serverless_cache" "main" {
  engine               = "valkey"
  name                 = local.name_prefix
  description          = "meet-me production Streams, rate limits, and DLQ"
  major_engine_version = "8"
  subnet_ids           = values(aws_subnet.private)[*].id
  security_group_ids   = [aws_security_group.cache.id]

  cache_usage_limits {
    data_storage {
      maximum = 1
      unit    = "GB"
    }

    ecpu_per_second {
      maximum = 1000
    }
  }

  daily_snapshot_time      = "17:00"
  snapshot_retention_limit = 1
}

