CREATE TABLE IF NOT EXISTS `ai_chat_session` (
  `chat_id` varchar(64) NOT NULL,
  `user_id` bigint unsigned NULL,
  `active_decision_session_id` bigint unsigned NULL,
  `last_decision_session_id` bigint unsigned NULL,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`chat_id`),
  KEY `idx_ai_chat_session_user_id` (`user_id`),
  KEY `idx_ai_chat_session_active_session` (`active_decision_session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
