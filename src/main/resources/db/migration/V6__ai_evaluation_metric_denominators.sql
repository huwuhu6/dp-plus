ALTER TABLE `ai_evaluation_run`
  ADD COLUMN `ranking_evaluated_count` int NOT NULL DEFAULT 0 AFTER `case_count`;

ALTER TABLE `ai_evaluation_case_result`
  MODIFY COLUMN `recall_at_k` decimal(8,4) NULL,
  MODIFY COLUMN `reciprocal_rank` decimal(8,4) NULL,
  MODIFY COLUMN `evidence_coverage_rate` decimal(8,4) NULL;
