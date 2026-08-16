ALTER TABLE `ai_conversation_evaluation_case`
  ADD COLUMN `expected_tool_names_json` json NULL AFTER `expected_routes_json`,
  ADD COLUMN `expected_city` varchar(64) NULL AFTER `expected_shop_ids`;

ALTER TABLE `ai_conversation_evaluation_run`
  ADD COLUMN `tool_matched_count` int NOT NULL DEFAULT 0 AFTER `route_matched_count`,
  ADD COLUMN `locality_matched_count` int NOT NULL DEFAULT 0 AFTER `tool_matched_count`;

ALTER TABLE `ai_conversation_evaluation_case_result`
  ADD COLUMN `actual_tool_names_json` json NULL AFTER `actual_routes_json`,
  ADD COLUMN `tool_matched` tinyint(1) NOT NULL DEFAULT 0 AFTER `route_matched`,
  ADD COLUMN `locality_matched` tinyint(1) NOT NULL DEFAULT 0 AFTER `tool_matched`;

UPDATE `ai_conversation_evaluation_case`
SET `expected_tool_names_json` = '["search_shop_evidence"]', `expected_city` = '福州市'
WHERE `case_code` = 'FUZHOU_NEARBY_JAPANESE_FOLLOW_UP';

UPDATE `ai_conversation_evaluation_case`
SET `expected_tool_names_json` = '["get_shop_detail"]', `expected_city` = '福州市'
WHERE `case_code` = 'FUZHOU_NEARBY_BARBECUE_FOLLOW_UP';

UPDATE `ai_conversation_evaluation_case`
SET `expected_tool_names_json` = '[]', `expected_city` = NULL
WHERE `case_code` = 'NON_DINING_GENERAL_CHAT';
