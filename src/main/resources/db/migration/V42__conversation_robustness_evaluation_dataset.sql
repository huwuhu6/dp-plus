INSERT INTO `tbl_ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_tool_names_json`, `expected_final_status`, `expected_city`, `active`, `notes`)
VALUES
('ROBUST_CHAT_INTERRUPT_AND_RESUME', 'conversation-robustness-v1',
 '[{"message":"帮我找福州附近的火锅","location":{"latitude":26.0789,"longitude":119.1945}},{"message":"你是谁？"},{"message":"继续，找安静一点的"}]',
 '["START_DECISION","GENERAL_CHAT","START_DECISION"]', '[]', 'COMPLETED|WAITING_RELAXATION', '福州市', true,
 '闲聊打断不得清空地点和餐饮上下文，后续条件细化应恢复推荐链路。'),
('ROBUST_PAUSED_DECISION_SWITCHES_DEMAND', 'conversation-robustness-v1',
 '[{"message":"在福州找人均50以内的米其林餐厅"},{"message":"算了，改成附近烧烤","location":{"latitude":26.0789,"longitude":119.1945}}]',
 '["START_DECISION","START_DECISION"]', '[]', 'COMPLETED|WAITING_RELAXATION', '福州市', true,
 '等待放宽时的新需求必须替代旧任务，禁止沿用旧菜系与预算。'),
('ROBUST_DECLINE_LOCATION_THEN_RECOVER', 'conversation-robustness-v1',
 '[{"message":"附近有什么日料"},{"message":"不提供位置，按全城搜索","selectedOptionId":"DECLINE_LOCATION"},{"message":"我在福州鼓楼，附近再找找"}]',
 '["START_DECISION","DECISION_EVENT","START_DECISION"]', '[]', 'COMPLETED|WAITING_RELAXATION', '福州市', true,
 '拒绝设备定位后仍可通过命名地点恢复搜索，旧拒绝状态不得阻塞。'),
('ROBUST_CITY_SWITCH_INVALIDATES_CANDIDATES', 'conversation-robustness-v1',
 '[{"message":"帮我找杭州附近的日料","location":{"latitude":30.2741,"longitude":120.1551}},{"message":"改成福州鼓楼附近"}]',
 '["START_DECISION","START_DECISION"]', '[]', 'COMPLETED|WAITING_RELAXATION', '福州市', true,
 '切换城市后候选池与焦点商户必须失效，不得继续返回杭州商户。'),
('ROBUST_INVALID_ACTION_THEN_RECOVER', 'conversation-robustness-v1',
 '[{"message":"附近有什么日料"},{"message":"继续","selectedOptionId":"INVALID_RELAXATION_OPTION"},{"message":"我在福州鼓楼，附近找烧烤"}]',
 '["START_DECISION","ERROR","START_DECISION"]', '[]', 'COMPLETED|WAITING_RELAXATION', '福州市', true,
 '非法操作事件不得污染会话；后续有效新需求必须能够在同一 chat 中恢复。')
ON DUPLICATE KEY UPDATE
 `turns_json`=VALUES(`turns_json`), `expected_routes_json`=VALUES(`expected_routes_json`),
 `expected_tool_names_json`=VALUES(`expected_tool_names_json`), `expected_final_status`=VALUES(`expected_final_status`),
 `expected_city`=VALUES(`expected_city`), `notes`=VALUES(`notes`), `active`=true;
