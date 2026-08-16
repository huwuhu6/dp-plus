ALTER TABLE `ai_decision_metric`
  ADD COLUMN `prompt_token_count` int NOT NULL DEFAULT 0 AFTER `model_failure_count`,
  ADD COLUMN `completion_token_count` int NOT NULL DEFAULT 0 AFTER `prompt_token_count`;

ALTER TABLE `ai_evaluation_case_result`
  ADD COLUMN `prompt_token_count` int NOT NULL DEFAULT 0 AFTER `model_failure_count`,
  ADD COLUMN `completion_token_count` int NOT NULL DEFAULT 0 AFTER `prompt_token_count`;

ALTER TABLE `ai_evaluation_run`
  ADD COLUMN `prompt_token_count` int NOT NULL DEFAULT 0 AFTER `model_failure_count`,
  ADD COLUMN `completion_token_count` int NOT NULL DEFAULT 0 AFTER `prompt_token_count`;
