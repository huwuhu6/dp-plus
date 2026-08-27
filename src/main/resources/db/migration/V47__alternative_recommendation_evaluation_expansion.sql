ALTER TABLE `tbl_ai_conversation_evaluation_case`
  ADD COLUMN `expected_unseen_pairs_json` json NULL AFTER `expected_unseen_from_turn`;

INSERT INTO `tbl_ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_context_rewrites_json`,
 `expected_tool_names_json`, `expected_final_status`, `expected_city`, `expected_unseen_pairs_json`, `active`, `notes`)
VALUES
('ALTERNATIVE_THREE_ROUNDS_STAY_UNSEEN', 'conversation-v1',
 '[{"message":"帮我找福州附近适合朋友聚餐的餐厅","location":{"latitude":26.05367074313305,"longitude":119.187378}},{"message":"换几家看看"},{"message":"再换一批"}]',
 '["START_DECISION","START_DECISION","START_DECISION"]',
 '[null,{"applied":true,"reason":"ALTERNATIVE_RECOMMENDATION"},{"applied":true,"reason":"ALTERNATIVE_RECOMMENDATION"}]',
 '[]', 'COMPLETED|WAITING_RELAXATION', '福州市', '[[1,2],[1,3],[2,3]]', true,
 '连续两次换店时，每一轮新推荐都不得复用任一已展示商户。'),
('ALTERNATIVE_REFINEMENT_KEEPS_UNSEEN_POOL', 'conversation-v1',
 '[{"message":"帮我找福州附近适合朋友聚餐的餐厅","location":{"latitude":26.05367074313305,"longitude":119.187378}},{"message":"换几家，人均100以内，安静一点"}]',
 '["START_DECISION","START_DECISION"]',
 '[null,{"applied":true,"reason":"ALTERNATIVE_RECOMMENDATION"}]',
 '[]', 'COMPLETED|WAITING_RELAXATION', '福州市', '[[1,2]]', true,
 '换店同时收紧预算和安静偏好时，保留条件合并能力并排除已展示商户。'),
('ALTERNATIVE_AFTER_FACT_FOLLOW_UP_STAYS_UNSEEN', 'conversation-v1',
 '[{"message":"帮我找福州附近的日料","location":{"latitude":26.05367074313305,"longitude":119.187378}},{"message":"第一家评价如何？"},{"message":"那换几家看看"}]',
 '["START_DECISION","BUSINESS_FOLLOW_UP","START_DECISION"]',
 '[null,{"applied":true,"candidateOrdinal":1},{"applied":true,"reason":"ALTERNATIVE_RECOMMENDATION"}]',
 '["search_shop_evidence"]', 'COMPLETED|WAITING_RELAXATION', '福州市', '[[1,3]]', true,
 '商户详情追问不应清空候选池；其后的换店仍必须避开首轮商户。'),
('ALTERNATIVE_WORDING_RECOMMEND_DIFFERENT_SHOPS', 'conversation-holdout-v1',
 '[{"message":"帮我找福州附近适合聚餐的餐厅","location":{"latitude":26.05367074313305,"longitude":119.187378}},{"message":"给我再推荐几家不同的"}]',
 '["START_DECISION","START_DECISION"]',
 '[null,{"applied":true,"reason":"ALTERNATIVE_RECOMMENDATION"}]',
 '[]', 'COMPLETED|WAITING_RELAXATION', '福州市', '[[1,2]]', true,
 '留出集验证“再推荐几家不同的”不会回到首轮候选。'),
('ALTERNATIVE_WORDING_OTHER_RESTAURANTS', 'conversation-holdout-v1',
 '[{"message":"帮我找福州附近的火锅","location":{"latitude":26.05367074313305,"longitude":119.187378}},{"message":"还有别的餐厅吗"}]',
 '["START_DECISION","START_DECISION"]',
 '[null,{"applied":true,"reason":"ALTERNATIVE_RECOMMENDATION"}]',
 '[]', 'COMPLETED|WAITING_RELAXATION', '福州市', '[[1,2]]', true,
 '留出集验证“还有别的餐厅吗”作为替代推荐，而不是详情追问。'),
('ALTERNATIVE_WORDING_CHANGE_BATCH', 'conversation-holdout-v1',
 '[{"message":"帮我找福州附近适合约会的餐厅","location":{"latitude":26.05367074313305,"longitude":119.187378}},{"message":"换一批吧"}]',
 '["START_DECISION","START_DECISION"]',
 '[null,{"applied":true,"reason":"ALTERNATIVE_RECOMMENDATION"}]',
 '[]', 'COMPLETED|WAITING_RELAXATION', '福州市', '[[1,2]]', true,
 '留出集验证“换一批”能够触发排除已展示商户的重新推荐。')
ON DUPLICATE KEY UPDATE
 `dataset_version` = VALUES(`dataset_version`),
 `turns_json` = VALUES(`turns_json`),
 `expected_routes_json` = VALUES(`expected_routes_json`),
 `expected_context_rewrites_json` = VALUES(`expected_context_rewrites_json`),
 `expected_tool_names_json` = VALUES(`expected_tool_names_json`),
 `expected_final_status` = VALUES(`expected_final_status`),
 `expected_city` = VALUES(`expected_city`),
 `expected_unseen_pairs_json` = VALUES(`expected_unseen_pairs_json`),
 `active` = true,
 `notes` = VALUES(`notes`);

UPDATE `tbl_ai_conversation_evaluation_case`
SET `expected_unseen_pairs_json` = '[[1,2]]'
WHERE `case_code` = 'ROBUST_CITY_SWITCH_INVALIDATES_CANDIDATES'
  AND `dataset_version` = 'conversation-robustness-v1';
