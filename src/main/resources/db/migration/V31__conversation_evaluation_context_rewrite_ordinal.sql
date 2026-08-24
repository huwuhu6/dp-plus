UPDATE `ai_conversation_evaluation_case`
SET `expected_context_rewrites_json` = '[null,{"applied":true,"candidateOrdinal":2}]',
    `notes` = '候选列表第二家指代应改写为上一轮第 2 个候选商户的独立追问，并保持在已完成推荐的商户追问链路。'
WHERE `case_code` = 'CONTEXT_REWRITE_SECOND_CANDIDATE';
