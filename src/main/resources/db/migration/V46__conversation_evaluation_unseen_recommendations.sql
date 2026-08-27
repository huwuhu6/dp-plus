ALTER TABLE `tbl_ai_conversation_evaluation_case`
  ADD COLUMN `expected_unseen_from_turn` int NULL AFTER `expected_memory_json`;

ALTER TABLE `tbl_ai_conversation_evaluation_case_result`
  ADD COLUMN `unseen_recommendations_matched` tinyint(1) NULL AFTER `memory_matched`;

INSERT INTO `tbl_ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_context_rewrites_json`,
 `expected_tool_names_json`, `expected_final_status`, `expected_unseen_from_turn`, `active`, `notes`)
VALUES
('ALTERNATIVE_RECOMMENDATIONS_ARE_UNSEEN', 'conversation-v1',
 '[{"message":"帮我找福州附近适合朋友聚餐的餐厅","location":{"latitude":26.05367074313305,"longitude":119.187378}},{"message":"换几家看看"}]',
 '["START_DECISION","START_DECISION"]',
 '[null,{"applied":true,"reason":"ALTERNATIVE_RECOMMENDATION"}]',
 '[]', 'COMPLETED|WAITING_RELAXATION', 1, true,
 '用户要求换几家时，第二轮必须重新进入推荐链路，且不得复用第一轮已经展示的商户。')
ON DUPLICATE KEY UPDATE
 `dataset_version` = VALUES(`dataset_version`),
 `turns_json` = VALUES(`turns_json`),
 `expected_routes_json` = VALUES(`expected_routes_json`),
 `expected_context_rewrites_json` = VALUES(`expected_context_rewrites_json`),
 `expected_tool_names_json` = VALUES(`expected_tool_names_json`),
 `expected_final_status` = VALUES(`expected_final_status`),
 `expected_unseen_from_turn` = VALUES(`expected_unseen_from_turn`),
 `active` = true,
 `notes` = VALUES(`notes`);
