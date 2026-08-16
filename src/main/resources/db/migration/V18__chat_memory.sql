CREATE TABLE IF NOT EXISTS `ai_chat_message` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `chat_id` varchar(64) NOT NULL,
  `user_id` bigint unsigned NULL,
  `decision_session_id` bigint unsigned NULL,
  `role` varchar(16) NOT NULL,
  `route` varchar(32) NULL,
  `content` text NOT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_chat_message_chat_id` (`chat_id`, `id`),
  KEY `idx_ai_chat_message_session_id` (`decision_session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
