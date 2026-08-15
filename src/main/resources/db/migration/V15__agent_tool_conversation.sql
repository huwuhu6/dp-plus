ALTER TABLE `ai_decision_session`
  ADD COLUMN `agent_context_json` text NULL AFTER `pending_options_json`;

CREATE TABLE IF NOT EXISTS `ai_agent_tool_call` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_id` bigint unsigned NOT NULL,
  `turn_no` int NOT NULL,
  `tool_name` varchar(64) NOT NULL,
  `tool_input_json` text NOT NULL,
  `tool_output_json` longtext NOT NULL,
  `status` varchar(16) NOT NULL,
  `duration_ms` bigint NOT NULL DEFAULT 0,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_agent_tool_call_session_turn` (`session_id`, `turn_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
