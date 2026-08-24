INSERT INTO `ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_tool_names_json`,
 `expected_final_status`, `expected_city`, `notes`)
VALUES
('EXPLICIT_DESTINATION_OVERRIDES_DEVICE_LOCATION', 'conversation-v1',
 '[{"message":"帮我找重庆附近的火锅","location":{"latitude":26.05367074313305,"longitude":119.187378}}, {"message":"确认使用重庆作为搜索位置","selectedOptionId":"CONFIRM_RESOLVED_LOCATION_0"}]',
 '["LOCATION_RESOLUTION","DECISION_EVENT"]', '[]', 'WAITING_RELAXATION', NULL,
 '设备定位在福州时，用户明确查询重庆。系统必须先解析并确认重庆作为 searchLocation，不能复用或覆盖福州 deviceLocation；当前样例库未覆盖重庆商户时，确认后进入 WAITING_RELAXATION 是正确结果。')
ON DUPLICATE KEY UPDATE
 `turns_json` = VALUES(`turns_json`),
 `expected_routes_json` = VALUES(`expected_routes_json`),
 `expected_tool_names_json` = VALUES(`expected_tool_names_json`),
 `expected_final_status` = VALUES(`expected_final_status`),
 `expected_city` = VALUES(`expected_city`),
 `notes` = VALUES(`notes`),
 `active` = 1;
