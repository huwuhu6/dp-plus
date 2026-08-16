INSERT INTO `ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_tool_names_json`, `expected_final_status`, `expected_shop_ids`, `expected_city`, `notes`) VALUES
('HOLDOUT_GENERAL_IDENTITY', 'conversation-holdout-v1',
 '[{"message":"你是谁"}]', '["GENERAL_CHAT"]', '[]', NULL, '', NULL,
 '闲聊身份询问不得进入餐饮决策。'),
('HOLDOUT_FUZHOU_NEW_RECOMMENDATION', 'conversation-holdout-v1',
 '[{"message":"帮我找附近的日料","location":{"latitude":26.0789,"longitude":119.1945}},{"message":"那有适合约会且安静的地方吗"}]',
 '["START_DECISION","START_DECISION"]', '[]', 'COMPLETED', '', '福州市',
 '完成推荐后出现新的场景筛选，应发起新决策并复用福州位置。'),
('HOLDOUT_LOCATION_DECLINED_CITYWIDE', 'conversation-holdout-v1',
 '[{"message":"推荐一家日料"},{"message":"不提供位置，按全城搜索","selectedOptionId":"DECLINE_LOCATION"}]',
 '["START_DECISION","DECISION_EVENT"]', '[]', 'COMPLETED', '', NULL,
 '用户明确拒绝定位后应按全城继续，不能再次索要位置。'),
('HOLDOUT_LOCATION_OVERRIDE', 'conversation-holdout-v1',
 '[{"message":"帮我找附近的日料","location":{"latitude":26.0789,"longitude":119.1945}},{"message":"帮我找附近的火锅","location":{"latitude":30.2741,"longitude":120.1551}}]',
 '["START_DECISION","START_DECISION"]', '[]', 'COMPLETED', '', '杭州市',
 '第二轮浏览器位置覆盖第一轮福州位置，最终推荐不得跨城。')
ON DUPLICATE KEY UPDATE
 `turns_json`=VALUES(`turns_json`), `expected_routes_json`=VALUES(`expected_routes_json`),
 `expected_tool_names_json`=VALUES(`expected_tool_names_json`), `expected_final_status`=VALUES(`expected_final_status`),
 `expected_city`=VALUES(`expected_city`), `notes`=VALUES(`notes`), `active`=1;
