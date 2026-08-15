CREATE TABLE IF NOT EXISTS `ai_decision_metric` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_id` bigint unsigned NOT NULL,
  `total_duration_ms` bigint NOT NULL DEFAULT 0,
  `extracting_duration_ms` bigint NOT NULL DEFAULT 0,
  `retrieving_duration_ms` bigint NOT NULL DEFAULT 0,
  `reranking_duration_ms` bigint NOT NULL DEFAULT 0,
  `answering_duration_ms` bigint NOT NULL DEFAULT 0,
  `model_call_count` int NOT NULL DEFAULT 0,
  `model_success_count` int NOT NULL DEFAULT 0,
  `model_failure_count` int NOT NULL DEFAULT 0,
  `initial_candidate_count` int NOT NULL DEFAULT 0,
  `hard_matched_candidate_count` int NOT NULL DEFAULT 0,
  `final_candidate_count` int NOT NULL DEFAULT 0,
  `relaxation_count` int NOT NULL DEFAULT 0,
  `evidence_covered_candidate_count` int NOT NULL DEFAULT 0,
  `evidence_coverage_rate` decimal(5,4) NOT NULL DEFAULT 0,
  `factual_consistent` tinyint(1) NOT NULL DEFAULT 1,
  `narrative_rejected` tinyint(1) NOT NULL DEFAULT 0,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_metric_session_id` (`session_id`),
  KEY `idx_ai_metric_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 最近一次运行的可观测指标。用于演示和调参，不作为离线质量评估的替代。
-- SELECT * FROM ai_decision_metric ORDER BY id DESC;
