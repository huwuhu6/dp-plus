ALTER TABLE `ai_evaluation_case`
  ADD COLUMN `expected_final_status` varchar(32) NULL AFTER `expected_status`,
  ADD COLUMN `follow_up_option_id` varchar(64) NULL AFTER `expected_constraints_json`,
  ADD COLUMN `follow_up_latitude` double NULL AFTER `follow_up_option_id`,
  ADD COLUMN `follow_up_longitude` double NULL AFTER `follow_up_latitude`;

ALTER TABLE `ai_evaluation_case_result`
  ADD COLUMN `initial_status` varchar(32) NULL AFTER `session_id`,
  ADD COLUMN `final_status` varchar(32) NULL AFTER `status_matched`,
  ADD COLUMN `final_status_matched` tinyint(1) NULL AFTER `final_status`;

ALTER TABLE `ai_evaluation_run`
  ADD COLUMN `follow_up_evaluated_count` int NOT NULL DEFAULT 0 AFTER `ranking_evaluated_count`,
  ADD COLUMN `follow_up_status_matched_count` int NOT NULL DEFAULT 0 AFTER `follow_up_evaluated_count`;

UPDATE `ai_evaluation_case`
SET `expected_final_status` = 'COMPLETED',
    `follow_up_latitude` = 30.3252,
    `follow_up_longitude` = 120.1505,
    `expected_shop_ids` = '8'
WHERE `case_code` = 'NEARBY_JAPANESE_NEEDS_LOCATION';

UPDATE `ai_evaluation_case`
SET `expected_final_status` = 'COMPLETED',
    `follow_up_option_id` = 'EXPAND_RADIUS',
    `expected_shop_ids` = '8'
WHERE `case_code` = 'JAPANESE_RADIUS_TOO_SMALL';
