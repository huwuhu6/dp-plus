UPDATE `ai_evaluation_case_result`
SET `model_failure_count` = GREATEST(`model_call_count` - `model_success_count`, 0)
WHERE `model_failure_count` = 0
  AND `model_call_count` > `model_success_count`;

UPDATE `ai_evaluation_run`
SET `model_failure_count` = GREATEST(`model_call_count` - `model_success_count`, 0)
WHERE `model_failure_count` = 0
  AND `model_call_count` > `model_success_count`;
