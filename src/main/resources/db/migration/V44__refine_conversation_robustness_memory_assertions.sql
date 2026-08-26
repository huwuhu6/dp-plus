UPDATE `tbl_ai_conversation_evaluation_case`
SET `expected_memory_json` = JSON_OBJECT('searchCity', '福州', 'dialogPhase', 'WAITING_RELAXATION', 'candidatePoolEmpty', true)
WHERE `dataset_version` = 'conversation-robustness-v1'
  AND `case_code` <> 'ROBUST_PAUSED_DECISION_SWITCHES_DEMAND';

UPDATE `tbl_ai_conversation_evaluation_case`
SET `expected_memory_json` = JSON_OBJECT('searchCity', '福州', 'dialogPhase', 'WAITING_RELAXATION',
    'candidatePoolEmpty', true, 'cuisine', '烧烤', 'budgetPerPerson', -1, 'hardConstraintsEmpty', true)
WHERE `case_code` = 'ROBUST_PAUSED_DECISION_SWITCHES_DEMAND';
