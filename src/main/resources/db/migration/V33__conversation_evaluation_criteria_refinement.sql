INSERT INTO `ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_tool_names_json`,
 `expected_final_status`, `expected_city`, `notes`)
VALUES
('CRITERIA_REFINEMENT_RETAINS_LOCATION', 'conversation-v1',
 '[{"message":"帮我找附近人均200以内的川菜","location":{"latitude":26.05367074313305,"longitude":119.187378}},{"message":"不要辣的，换成粤菜，重新推荐","location":{"latitude":26.05367074313305,"longitude":119.187378}}]',
 '["START_DECISION","START_DECISION"]', '[]', 'COMPLETED', '福州市',
 '第二轮显式替换菜系并提出清淡偏好时，应作为新的推荐任务执行，同时复用会话位置；条件合并由单元测试断言预算、半径等未提及条件不会丢失。')
ON DUPLICATE KEY UPDATE
 `turns_json` = VALUES(`turns_json`),
 `expected_routes_json` = VALUES(`expected_routes_json`),
 `expected_tool_names_json` = VALUES(`expected_tool_names_json`),
 `expected_final_status` = VALUES(`expected_final_status`),
 `expected_city` = VALUES(`expected_city`),
 `notes` = VALUES(`notes`),
 `active` = 1;
