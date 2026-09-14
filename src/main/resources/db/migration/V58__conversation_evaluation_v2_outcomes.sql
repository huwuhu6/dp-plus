ALTER TABLE `tbl_ai_conversation_evaluation_case_result`
  ADD COLUMN `actual_v2_outcomes_json` json NULL AFTER `turn_outputs_json`,
  ADD COLUMN `expected_v2_outcome_count` int NOT NULL DEFAULT 0 AFTER `actual_v2_outcomes_json`,
  ADD COLUMN `matched_v2_outcome_count` int NOT NULL DEFAULT 0 AFTER `expected_v2_outcome_count`,
  ADD COLUMN `v2_outcome_matched` tinyint(1) NULL AFTER `matched_v2_outcome_count`;

ALTER TABLE `tbl_ai_conversation_evaluation_run`
  ADD COLUMN `v2_outcome_matched_count` int NOT NULL DEFAULT 0 AFTER `unseen_recommendation_matched_count`;
