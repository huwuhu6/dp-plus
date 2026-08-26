ALTER TABLE `tbl_ai_conversation_evaluation_case`
  ADD COLUMN `expected_error_count` int NOT NULL DEFAULT 0 AFTER `expected_city`,
  ADD COLUMN `expected_recovery_routes_json` json NULL AFTER `expected_error_count`,
  ADD COLUMN `expected_memory_json` json NULL AFTER `expected_recovery_routes_json`;

ALTER TABLE `tbl_ai_conversation_evaluation_case_result`
  ADD COLUMN `actual_error_count` int NOT NULL DEFAULT 0 AFTER `completion_token_count`,
  ADD COLUMN `recovery_matched` tinyint(1) NULL AFTER `actual_error_count`,
  ADD COLUMN `memory_matched` tinyint(1) NULL AFTER `recovery_matched`;

UPDATE `tbl_ai_conversation_evaluation_case`
SET `expected_memory_json` = JSON_OBJECT('searchCity', '福州市')
WHERE `dataset_version` = 'conversation-robustness-v1'
  AND `case_code` IN ('ROBUST_CHAT_INTERRUPT_AND_RESUME', 'ROBUST_PAUSED_DECISION_SWITCHES_DEMAND',
                      'ROBUST_DECLINE_LOCATION_THEN_RECOVER', 'ROBUST_CITY_SWITCH_INVALIDATES_CANDIDATES');

UPDATE `tbl_ai_conversation_evaluation_case`
SET `expected_error_count` = 1,
    `expected_recovery_routes_json` = JSON_ARRAY('START_DECISION'),
    `expected_memory_json` = JSON_OBJECT('searchCity', '福州市')
WHERE `case_code` = 'ROBUST_INVALID_ACTION_THEN_RECOVER'
  AND `dataset_version` = 'conversation-robustness-v1';
