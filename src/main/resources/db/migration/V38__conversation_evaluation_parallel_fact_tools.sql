INSERT INTO `ai_conversation_evaluation_case`
(`case_code`, `dataset_version`, `turns_json`, `expected_routes_json`, `expected_tool_names_json`,
 `expected_final_status`, `expected_city`, `notes`)
VALUES
('COMPOUND_FACTS_VOUCHER_AND_EVIDENCE', 'conversation-v1',
 '[{"message":"推荐一家杭州适合约会的日料","location":{"latitude":30.2741,"longitude":120.1551}}, {"message":"这家评价如何，还有优惠券吗？","location":{"latitude":30.2741,"longitude":120.1551}}]',
 '["START_DECISION","BUSINESS_FOLLOW_UP"]', '["query_shop_vouchers","search_shop_evidence"]', 'COMPLETED', '杭州市',
 '同一轮同时询问已推荐商户的评价和优惠券。两项均为独立只读事实，必须在同一轮工具审计中覆盖；编排器可并行执行并按规划顺序聚合，任一工具失败不得阻断另一项事实返回。')
ON DUPLICATE KEY UPDATE
 `turns_json` = VALUES(`turns_json`),
 `expected_routes_json` = VALUES(`expected_routes_json`),
 `expected_tool_names_json` = VALUES(`expected_tool_names_json`),
 `expected_final_status` = VALUES(`expected_final_status`),
 `expected_city` = VALUES(`expected_city`),
 `notes` = VALUES(`notes`),
 `active` = 1;
