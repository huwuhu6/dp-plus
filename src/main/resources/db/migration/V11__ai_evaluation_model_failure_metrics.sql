ALTER TABLE `ai_evaluation_case_result`
  ADD COLUMN `model_failure_count` int NOT NULL DEFAULT 0 AFTER `model_success_count`;

ALTER TABLE `ai_evaluation_run`
  ADD COLUMN `model_failure_count` int NOT NULL DEFAULT 0 AFTER `model_success_count`;
