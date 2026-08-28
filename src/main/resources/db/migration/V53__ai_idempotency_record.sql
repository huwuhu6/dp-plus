CREATE TABLE IF NOT EXISTS `tbl_ai_idempotency_record` (
  `id` bigint unsigned NOT NULL,
  `user_id` bigint unsigned NOT NULL DEFAULT 0,
  `scope` varchar(48) NOT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `status` varchar(16) NOT NULL,
  `result_reference` varchar(128) NULL,
  `result_json` json NULL,
  `error_message` varchar(512) NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_idempotency_scope_user_key` (`scope`, `user_id`, `idempotency_key`),
  KEY `idx_ai_idempotency_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Retain completed records for 7 days initially. Purge scheduling is intentionally deferred.
