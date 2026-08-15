ALTER TABLE `ai_evaluation_case`
  ADD COLUMN `dataset_version` varchar(128) NOT NULL DEFAULT 'seed-v2' AFTER `case_code`,
  ADD KEY `idx_ai_evaluation_case_dataset_active` (`dataset_version`, `active`, `id`);

INSERT INTO `ai_evaluation_case`
(`case_code`, `dataset_version`, `query_text`, `latitude`, `longitude`, `max_candidates`, `expected_status`, `expected_final_status`, `expected_shop_ids`, `expected_constraints_json`, `follow_up_option_id`, `notes`) VALUES
('HOLDOUT_DATE_STEAK', 'holdout-v1', '带对象吃安静的牛排，人均300', 30.3127, 120.1467, 3, 'COMPLETED', NULL, '4', '{"cuisine":"西餐","budgetPerPerson":300,"occasion":"约会","quiet":true}', NULL, '改写约会西餐表达。'),
('HOLDOUT_SUSHI_METERS', 'holdout-v1', '周边100米内的寿司', 30.3127, 120.1467, 3, 'WAITING_RELAXATION', 'COMPLETED', '8', '{"cuisine":"日料","radiusKm":0.1,"nearby":true}', 'EXPAND_RADIUS', '米单位和放宽续聊。'),
('HOLDOUT_JAPANESE_LOW_BUDGET', 'holdout-v1', '寿司人均10元', 30.3252, 120.1505, 3, 'WAITING_RELAXATION', NULL, '', '{"cuisine":"日料","budgetPerPerson":10}', NULL, '同义菜系与预算硬约束。'),
('HOLDOUT_HOT_POT_QUEUE', 'holdout-v1', '火锅人均150，尽量别排队', 30.3186, 120.1486, 3, 'COMPLETED', NULL, '6', '{"cuisine":"火锅","budgetPerPerson":150,"avoidQueue":true}', NULL, '不同排队表达。')
ON DUPLICATE KEY UPDATE
  `query_text`=VALUES(`query_text`), `latitude`=VALUES(`latitude`), `longitude`=VALUES(`longitude`),
  `expected_status`=VALUES(`expected_status`), `expected_final_status`=VALUES(`expected_final_status`),
  `expected_shop_ids`=VALUES(`expected_shop_ids`), `expected_constraints_json`=VALUES(`expected_constraints_json`),
  `follow_up_option_id`=VALUES(`follow_up_option_id`), `notes`=VALUES(`notes`), `active`=1;
