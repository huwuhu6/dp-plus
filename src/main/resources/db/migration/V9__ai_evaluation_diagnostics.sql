ALTER TABLE `ai_evaluation_case_result`
  ADD COLUMN `constraint_mismatch` varchar(255) NULL AFTER `constraint_matched`;

UPDATE `ai_evaluation_case`
SET `expected_shop_ids` = '5,6,2'
WHERE `case_code` = 'HOT_POT_LATE_NIGHT';
