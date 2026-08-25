ALTER TABLE `ai_decision_metric`
  ADD COLUMN `strict_candidate_count` int NOT NULL DEFAULT 0 AFTER `hard_matched_candidate_count`,
  ADD COLUMN `automatic_relaxation_applied` tinyint(1) NOT NULL DEFAULT 0 AFTER `relaxation_count`,
  ADD COLUMN `result_evaluation_outcome` varchar(64) NOT NULL DEFAULT 'UNKNOWN' AFTER `automatic_relaxation_applied`;

INSERT INTO `ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_tool_names_json`,
 `expected_final_status`, `expected_city`, `notes`)
VALUES
('AUTO_EXPAND_DEFAULT_NEARBY_RADIUS', 'conversation-v1',
 '[{"message":"附近的火锅","location":{"latitude":26.0400,"longitude":119.2000}}]',
 '["START_DECISION"]', '[]', 'COMPLETED', '福州市',
 '用户只表达“附近”且未给距离时，严格默认 3km 无火锅候选可自动扩展至 5km；地点、菜系、预算、到店时间和用户表达的偏好必须保持不变。')
ON DUPLICATE KEY UPDATE
 `turns_json` = VALUES(`turns_json`),
 `expected_routes_json` = VALUES(`expected_routes_json`),
 `expected_tool_names_json` = VALUES(`expected_tool_names_json`),
 `expected_final_status` = VALUES(`expected_final_status`),
 `expected_city` = VALUES(`expected_city`),
 `notes` = VALUES(`notes`),
 `active` = 1;
