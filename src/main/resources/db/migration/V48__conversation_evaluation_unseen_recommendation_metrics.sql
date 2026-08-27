ALTER TABLE `tbl_ai_conversation_evaluation_run`
  ADD COLUMN `unseen_recommendation_expected_count` int NOT NULL DEFAULT 0 AFTER `shop_matched_count`,
  ADD COLUMN `unseen_recommendation_matched_count` int NOT NULL DEFAULT 0 AFTER `unseen_recommendation_expected_count`;
