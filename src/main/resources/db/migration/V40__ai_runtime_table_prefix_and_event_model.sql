-- Phase 1 only renames AI-owned tables. Legacy business tb_* tables are migrated separately.
RENAME TABLE
  `ai_shop_profile` TO `tbl_ai_shop_profile`,
  `ai_review_document` TO `tbl_ai_review_document`,
  `ai_decision_session` TO `tbl_ai_decision_session`,
  `ai_decision_step` TO `tbl_ai_decision_step`,
  `ai_decision_metric` TO `tbl_ai_decision_metric`,
  `ai_decision_message` TO `tbl_ai_decision_message`,
  `ai_agent_tool_call` TO `tbl_ai_agent_tool_call`,
  `ai_chat_message` TO `tbl_ai_chat_message`,
  `ai_chat_session` TO `tbl_ai_chat_session_legacy`,
  `ai_evaluation_case` TO `tbl_ai_evaluation_case`,
  `ai_evaluation_run` TO `tbl_ai_evaluation_run`,
  `ai_evaluation_case_result` TO `tbl_ai_evaluation_case_result`,
  `ai_conversation_evaluation_case` TO `tbl_ai_conversation_evaluation_case`,
  `ai_conversation_evaluation_run` TO `tbl_ai_conversation_evaluation_run`,
  `ai_conversation_evaluation_case_result` TO `tbl_ai_conversation_evaluation_case_result`;

CREATE TABLE IF NOT EXISTS `tbl_ai_working_memory` (
  `id` bigint unsigned NOT NULL,
  `chat_id` varchar(64) NOT NULL,
  `user_id` bigint unsigned NULL,
  `version` int NOT NULL,
  `memory_json` json NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_working_memory_chat_version` (`chat_id`, `version`),
  KEY `idx_ai_working_memory_user_chat` (`user_id`, `chat_id`),
  KEY `idx_ai_working_memory_chat_version` (`chat_id`, `version` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tbl_ai_conversation_event` (
  `id` bigint unsigned NOT NULL,
  `chat_id` varchar(64) NOT NULL,
  `trace_id` varchar(64) NOT NULL,
  `turn_no` int NOT NULL DEFAULT 0,
  `sequence_no` int NOT NULL,
  `event_type` varchar(48) NOT NULL,
  `status` varchar(16) NOT NULL,
  `working_memory_id` bigint unsigned NULL,
  `parent_event_id` bigint unsigned NULL,
  `event_result` json NULL,
  `metadata` json NULL,
  `started_at` datetime(3) NULL,
  `ended_at` datetime(3) NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_event_trace_sequence` (`trace_id`, `sequence_no`),
  KEY `idx_ai_event_chat_turn` (`chat_id`, `turn_no`, `id`),
  KEY `idx_ai_event_parent` (`parent_event_id`),
  CONSTRAINT `ck_ai_event_status` CHECK (`status` IN ('RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `tbl_ai_decision_session`
  ADD COLUMN `chat_id` varchar(64) NULL AFTER `user_id`,
  ADD COLUMN `trace_id` varchar(64) NULL AFTER `chat_id`,
  ADD COLUMN `started_event_id` bigint unsigned NULL AFTER `trace_id`,
  ADD KEY `idx_ai_decision_session_chat` (`chat_id`, `create_time`),
  ADD KEY `idx_ai_decision_session_trace` (`trace_id`);

-- Preserve old single-row snapshots for audit. New writes use tbl_ai_working_memory only.
INSERT INTO `tbl_ai_working_memory` (`id`, `chat_id`, `user_id`, `version`, `memory_json`, `created_at`)
SELECT UUID_SHORT(), `chat_id`, `user_id`, GREATEST(`version`, 1),
       COALESCE(`working_memory_json`, JSON_OBJECT()), `update_time`
FROM `tbl_ai_chat_session_legacy`
WHERE `working_memory_json` IS NOT NULL
ON DUPLICATE KEY UPDATE `memory_json` = VALUES(`memory_json`);
