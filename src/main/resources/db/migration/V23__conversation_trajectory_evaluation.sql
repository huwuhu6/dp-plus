CREATE TABLE IF NOT EXISTS `ai_conversation_evaluation_case` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `case_code` varchar(64) NOT NULL,
  `dataset_version` varchar(64) NOT NULL,
  `turns_json` json NOT NULL,
  `expected_routes_json` json NOT NULL,
  `expected_final_status` varchar(32) NULL,
  `expected_shop_ids` varchar(255) NOT NULL DEFAULT '',
  `active` tinyint(1) NOT NULL DEFAULT 1,
  `notes` varchar(512) NOT NULL DEFAULT '',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_conversation_evaluation_case_code` (`case_code`),
  KEY `idx_ai_conversation_evaluation_case_dataset_active` (`dataset_version`, `active`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_conversation_evaluation_run` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NULL,
  `model` varchar(128) NULL,
  `dataset_version` varchar(64) NOT NULL,
  `case_count` int NOT NULL DEFAULT 0,
  `route_matched_count` int NOT NULL DEFAULT 0,
  `final_status_matched_count` int NOT NULL DEFAULT 0,
  `shop_matched_count` int NOT NULL DEFAULT 0,
  `completed_count` int NOT NULL DEFAULT 0,
  `avg_duration_ms` bigint NOT NULL DEFAULT 0,
  `status` varchar(32) NOT NULL,
  `error_summary` varchar(512) NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_conversation_evaluation_run_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_conversation_evaluation_case_result` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `run_id` bigint unsigned NOT NULL,
  `case_id` bigint unsigned NOT NULL,
  `chat_id` varchar(96) NOT NULL,
  `actual_routes_json` json NULL,
  `actual_final_status` varchar(32) NULL,
  `recommended_shop_ids` varchar(255) NOT NULL DEFAULT '',
  `route_matched` tinyint(1) NOT NULL DEFAULT 0,
  `final_status_matched` tinyint(1) NOT NULL DEFAULT 0,
  `shop_matched` tinyint(1) NOT NULL DEFAULT 0,
  `duration_ms` bigint NOT NULL DEFAULT 0,
  `turn_outputs_json` longtext NULL,
  `error_message` varchar(512) NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_conversation_evaluation_result_run` (`run_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_final_status`, `expected_shop_ids`, `notes`) VALUES
('FUZHOU_NEARBY_JAPANESE_FOLLOW_UP', 'conversation-v1',
 '[{"message":"帮我找附近的日料","location":{"latitude":26.0789,"longitude":119.1945}},{"message":"这个日本料理评价如何"}]',
 '["START_DECISION","BUSINESS_FOLLOW_UP"]', 'COMPLETED', '', '验证浏览器位置、餐饮新推荐和候选商户指代追问。'),
('FUZHOU_NEARBY_BARBECUE_FOLLOW_UP', 'conversation-v1',
 '[{"message":"帮我找附近的烤肉店","location":{"latitude":26.0789,"longitude":119.1945}},{"message":"这家营业到几点"}]',
 '["START_DECISION","BUSINESS_FOLLOW_UP"]', 'COMPLETED', '', '验证附近位置槽位、餐饮检索与单商户事实查询。'),
('NON_DINING_GENERAL_CHAT', 'conversation-v1',
 '[{"message":"附近有羽毛球馆推荐吗"}]',
 '["GENERAL_CHAT"]', NULL, '', '验证非餐饮需求不进入餐饮决策链路。')
ON DUPLICATE KEY UPDATE
 `turns_json`=VALUES(`turns_json`), `expected_routes_json`=VALUES(`expected_routes_json`),
 `expected_final_status`=VALUES(`expected_final_status`), `expected_shop_ids`=VALUES(`expected_shop_ids`),
 `notes`=VALUES(`notes`), `active`=1;
