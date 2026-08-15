CREATE TABLE IF NOT EXISTS `ai_evaluation_case` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `case_code` varchar(64) NOT NULL,
  `query_text` varchar(1024) NOT NULL,
  `latitude` double NULL,
  `longitude` double NULL,
  `max_candidates` int NOT NULL DEFAULT 3,
  `expected_status` varchar(32) NOT NULL DEFAULT 'COMPLETED',
  `expected_shop_ids` varchar(255) NOT NULL DEFAULT '',
  `active` tinyint(1) NOT NULL DEFAULT 1,
  `notes` varchar(512) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_evaluation_case_code` (`case_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_evaluation_run` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NULL,
  `case_count` int NOT NULL DEFAULT 0,
  `status_matched_count` int NOT NULL DEFAULT 0,
  `completed_count` int NOT NULL DEFAULT 0,
  `model_call_count` int NOT NULL DEFAULT 0,
  `model_success_count` int NOT NULL DEFAULT 0,
  `hard_constraint_violation_count` int NOT NULL DEFAULT 0,
  `factual_consistent_count` int NOT NULL DEFAULT 0,
  `recall_at_k` decimal(8,4) NOT NULL DEFAULT 0,
  `mrr` decimal(8,4) NOT NULL DEFAULT 0,
  `evidence_coverage_rate` decimal(8,4) NOT NULL DEFAULT 0,
  `status` varchar(32) NOT NULL,
  `error_summary` varchar(512) NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_evaluation_run_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_evaluation_case_result` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `run_id` bigint unsigned NOT NULL,
  `case_id` bigint unsigned NOT NULL,
  `session_id` bigint unsigned NULL,
  `actual_status` varchar(32) NOT NULL,
  `recommended_shop_ids` varchar(255) NOT NULL DEFAULT '',
  `status_matched` tinyint(1) NOT NULL DEFAULT 0,
  `recall_at_k` decimal(8,4) NOT NULL DEFAULT 0,
  `reciprocal_rank` decimal(8,4) NOT NULL DEFAULT 0,
  `hard_constraint_violated` tinyint(1) NOT NULL DEFAULT 0,
  `factual_consistent` tinyint(1) NOT NULL DEFAULT 0,
  `evidence_coverage_rate` decimal(8,4) NOT NULL DEFAULT 0,
  `model_call_count` int NOT NULL DEFAULT 0,
  `model_success_count` int NOT NULL DEFAULT 0,
  `error_message` varchar(512) NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_evaluation_case_result_run_id` (`run_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `ai_evaluation_case` (`case_code`, `query_text`, `latitude`, `longitude`, `max_candidates`, `expected_status`, `expected_shop_ids`, `notes`) VALUES
  ('DATE_WESTERN_QUIET', '晚上想和女朋友吃安静的西餐，人均300以内', 30.3127, 120.1467, 3, 'COMPLETED', '4', '约会、西餐、预算、安静和晚餐营业时间。'),
  ('NEARBY_JAPANESE', '找附近人均100的日料，想安静聊天', 30.3252, 120.1505, 3, 'COMPLETED', '8', '附近、日料、预算与安静偏好。'),
  ('HOT_POT_LOW_QUEUE', '晚上想吃火锅，不想排队', 30.3186, 120.1486, 3, 'COMPLETED', '6', '火锅、晚餐和低排队风险偏好。')
ON DUPLICATE KEY UPDATE
  `query_text` = VALUES(`query_text`), `latitude` = VALUES(`latitude`), `longitude` = VALUES(`longitude`),
  `max_candidates` = VALUES(`max_candidates`), `expected_status` = VALUES(`expected_status`),
  `expected_shop_ids` = VALUES(`expected_shop_ids`), `notes` = VALUES(`notes`), `active` = 1;
