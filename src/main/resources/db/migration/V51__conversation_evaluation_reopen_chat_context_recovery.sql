INSERT INTO `tbl_ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_context_rewrites_json`,
 `expected_tool_names_json`, `expected_final_status`, `expected_city`, `expected_memory_json`, `active`, `notes`)
VALUES
('ROBUST_REOPEN_CHAT_RESTORES_CONTEXT', 'conversation-robustness-v1',
 '[{"message":"帮我找福州附近的日料","location":{"latitude":26.0789,"longitude":119.1945}},{"message":"刚才第一家评价如何？"}]',
 '["START_DECISION","BUSINESS_FOLLOW_UP"]',
 '[null,{"applied":true,"candidateOrdinal":1}]',
 '["search_shop_evidence"]', 'COMPLETED', '福州市',
 '{"searchCity":"福州","candidatePoolEmpty":false,"focusedShopIdNull":false}', true,
 '模拟客户端重新打开聊天后仅携带同一 chatId；必须从持久化 Working Memory 恢复活动决策、候选池和第一家引用。')
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
