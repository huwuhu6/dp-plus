ALTER TABLE `ai_conversation_evaluation_case`
  ADD COLUMN `expected_context_rewrites_json` json NULL AFTER `expected_routes_json`;

ALTER TABLE `ai_conversation_evaluation_run`
  ADD COLUMN `context_rewrite_expected_count` int NOT NULL DEFAULT 0 AFTER `route_matched_count`,
  ADD COLUMN `context_rewrite_matched_count` int NOT NULL DEFAULT 0 AFTER `context_rewrite_expected_count`;

ALTER TABLE `ai_conversation_evaluation_case_result`
  ADD COLUMN `actual_context_rewrites_json` json NULL AFTER `actual_routes_json`,
  ADD COLUMN `expected_context_rewrite_count` int NOT NULL DEFAULT 0 AFTER `unexpected_tool_count`,
  ADD COLUMN `matched_context_rewrite_count` int NOT NULL DEFAULT 0 AFTER `expected_context_rewrite_count`,
  ADD COLUMN `context_rewrite_matched` tinyint(1) NULL AFTER `route_matched`;

INSERT INTO `ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_context_rewrites_json`,
 `expected_tool_names_json`, `expected_final_status`, `expected_shop_ids`, `expected_city`, `notes`)
VALUES
('CONTEXT_REWRITE_SECOND_CANDIDATE', 'conversation-v1',
 '[{"message":"帮我看看附近有没有什么好吃的","location":{"latitude":26.05367074313305,"longitude":119.187378}},{"message":"第二家怎么样？","location":{"latitude":26.05367074313305,"longitude":119.187378}}]',
 '["START_DECISION","BUSINESS_FOLLOW_UP"]',
 '[null,{"applied":true,"contains":"筑地日本料理"}]',
 '["get_shop_detail"]', 'COMPLETED', '74', '福州市',
 '候选列表第二家指代应改写为筑地日本料理（上街店）的独立追问，并保持在已完成推荐的商户追问链路。')
ON DUPLICATE KEY UPDATE
 `dataset_version` = VALUES(`dataset_version`),
 `turns_json` = VALUES(`turns_json`),
 `expected_routes_json` = VALUES(`expected_routes_json`),
 `expected_context_rewrites_json` = VALUES(`expected_context_rewrites_json`),
 `expected_tool_names_json` = VALUES(`expected_tool_names_json`),
 `expected_final_status` = VALUES(`expected_final_status`),
 `expected_shop_ids` = VALUES(`expected_shop_ids`),
 `expected_city` = VALUES(`expected_city`),
 `notes` = VALUES(`notes`),
 `active` = 1;
