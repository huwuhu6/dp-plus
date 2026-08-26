ALTER TABLE `tbl_ai_conversation_evaluation_run`
  ADD COLUMN `p50_duration_ms` bigint NOT NULL DEFAULT 0 AFTER `avg_duration_ms`,
  ADD COLUMN `p95_duration_ms` bigint NOT NULL DEFAULT 0 AFTER `p50_duration_ms`,
  ADD COLUMN `p99_duration_ms` bigint NOT NULL DEFAULT 0 AFTER `p95_duration_ms`,
  ADD COLUMN `error_rate` decimal(8,4) NOT NULL DEFAULT 0 AFTER `p99_duration_ms`,
  ADD COLUMN `model_call_count` int NOT NULL DEFAULT 0 AFTER `error_rate`,
  ADD COLUMN `model_success_count` int NOT NULL DEFAULT 0 AFTER `model_call_count`,
  ADD COLUMN `model_failure_count` int NOT NULL DEFAULT 0 AFTER `model_success_count`,
  ADD COLUMN `prompt_token_count` bigint NOT NULL DEFAULT 0 AFTER `model_failure_count`,
  ADD COLUMN `completion_token_count` bigint NOT NULL DEFAULT 0 AFTER `prompt_token_count`;

ALTER TABLE `tbl_ai_conversation_evaluation_case_result`
  ADD COLUMN `model_call_count` int NOT NULL DEFAULT 0 AFTER `duration_ms`,
  ADD COLUMN `model_success_count` int NOT NULL DEFAULT 0 AFTER `model_call_count`,
  ADD COLUMN `model_failure_count` int NOT NULL DEFAULT 0 AFTER `model_success_count`,
  ADD COLUMN `prompt_token_count` bigint NOT NULL DEFAULT 0 AFTER `model_failure_count`,
  ADD COLUMN `completion_token_count` bigint NOT NULL DEFAULT 0 AFTER `prompt_token_count`;
