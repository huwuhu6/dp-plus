ALTER TABLE `ai_evaluation_case_result`
  ADD COLUMN `total_duration_ms` bigint NULL AFTER `model_success_count`,
  ADD COLUMN `extracting_duration_ms` bigint NULL AFTER `total_duration_ms`;

ALTER TABLE `ai_evaluation_run`
  ADD COLUMN `avg_total_duration_ms` bigint NOT NULL DEFAULT 0 AFTER `model_success_count`,
  ADD COLUMN `p95_total_duration_ms` bigint NOT NULL DEFAULT 0 AFTER `avg_total_duration_ms`,
  ADD COLUMN `avg_extracting_duration_ms` bigint NOT NULL DEFAULT 0 AFTER `p95_total_duration_ms`;
