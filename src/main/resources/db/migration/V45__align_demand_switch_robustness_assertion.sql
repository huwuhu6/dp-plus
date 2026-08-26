UPDATE `tbl_ai_conversation_evaluation_case`
SET `expected_memory_json` = JSON_OBJECT('searchCity', '福州', 'dialogPhase', 'WAITING_RELAXATION',
    'candidatePoolEmpty', true, 'budgetPerPerson', -1, 'hardConstraintsEmpty', true)
WHERE `case_code` = 'ROBUST_PAUSED_DECISION_SWITCHES_DEMAND';
