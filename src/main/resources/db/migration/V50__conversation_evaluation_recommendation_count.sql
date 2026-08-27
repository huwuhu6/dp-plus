INSERT INTO `tbl_ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_context_rewrites_json`,
 `expected_tool_names_json`, `expected_final_status`, `expected_city`, `expected_memory_json`, `active`, `notes`)
VALUES
('RECOMMENDATION_COUNT_MORE_SHOPS', 'conversation-holdout-v1',
 '[{"message":"帮我找福州附近适合朋友聚餐的餐厅，多推荐几家","location":{"latitude":26.05367074313305,"longitude":119.187378}}]',
 '["START_DECISION"]', '[null]', '[]', 'COMPLETED', '福州市', '{"candidatePoolSize":5}', true,
 '用户表达“多推荐几家”时，应返回上限内的 5 家候选，而不是默认 3 家。')
ON DUPLICATE KEY UPDATE
 `turns_json` = VALUES(`turns_json`),
 `expected_routes_json` = VALUES(`expected_routes_json`),
 `expected_context_rewrites_json` = VALUES(`expected_context_rewrites_json`),
 `expected_tool_names_json` = VALUES(`expected_tool_names_json`),
 `expected_final_status` = VALUES(`expected_final_status`),
 `expected_city` = VALUES(`expected_city`),
 `expected_memory_json` = VALUES(`expected_memory_json`),
 `active` = VALUES(`active`),
 `notes` = VALUES(`notes`);
