ALTER TABLE `ai_decision_session`
  ADD COLUMN `request_context_json` text NULL AFTER `result_json`,
  ADD COLUMN `pending_type` varchar(32) NULL AFTER `request_context_json`,
  ADD COLUMN `pending_options_json` text NULL AFTER `pending_type`;

CREATE TABLE IF NOT EXISTS `ai_decision_message` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_id` bigint unsigned NOT NULL,
  `role` varchar(16) NOT NULL,
  `message_type` varchar(32) NOT NULL,
  `content` text NOT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_message_session_id` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `ai_decision_metric`
  ADD COLUMN `attempt_no` int NOT NULL DEFAULT 1 AFTER `session_id`;

ALTER TABLE `ai_decision_metric` DROP INDEX `uk_ai_metric_session_id`;
ALTER TABLE `ai_decision_metric` ADD UNIQUE KEY `uk_ai_metric_session_attempt` (`session_id`, `attempt_no`);
