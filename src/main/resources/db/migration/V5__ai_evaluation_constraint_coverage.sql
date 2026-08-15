ALTER TABLE `ai_evaluation_case`
  ADD COLUMN `expected_constraints_json` text NULL AFTER `expected_shop_ids`;

ALTER TABLE `ai_evaluation_run`
  ADD COLUMN `constraint_matched_count` int NOT NULL DEFAULT 0 AFTER `status_matched_count`;

ALTER TABLE `ai_evaluation_case_result`
  ADD COLUMN `constraint_matched` tinyint(1) NOT NULL DEFAULT 0 AFTER `status_matched`;

UPDATE `ai_evaluation_case`
SET `expected_constraints_json` = CASE `case_code`
  WHEN 'DATE_WESTERN_QUIET' THEN '{"cuisine":"西餐","budgetPerPerson":300,"arrivalTime":"19:00","occasion":"约会","quiet":true}'
  WHEN 'NEARBY_JAPANESE' THEN '{"cuisine":"日料","budgetPerPerson":100,"nearby":true,"quiet":true}'
  WHEN 'HOT_POT_LOW_QUEUE' THEN '{"cuisine":"火锅","arrivalTime":"19:00","avoidQueue":true}'
  ELSE `expected_constraints_json`
END;

INSERT INTO `ai_evaluation_case` (`case_code`, `query_text`, `latitude`, `longitude`, `max_candidates`, `expected_status`, `expected_shop_ids`, `expected_constraints_json`, `notes`) VALUES
  ('NEARBY_JAPANESE_NEEDS_LOCATION', '找附近人均100的日料', NULL, NULL, 3, 'CLARIFYING', '', '{"cuisine":"日料","budgetPerPerson":100,"nearby":true}', '附近意图缺坐标时必须澄清。'),
  ('JAPANESE_BUDGET_TOO_LOW', '人均10的日料', 30.3252, 120.1505, 3, 'WAITING_RELAXATION', '', '{"cuisine":"日料","budgetPerPerson":10}', '预算硬约束零候选。'),
  ('JAPANESE_RADIUS_TOO_SMALL', '附近100米的日料', 30.3127, 120.1467, 3, 'WAITING_RELAXATION', '', '{"cuisine":"日料","radiusKm":0.1,"nearby":true}', '距离硬约束零候选。'),
  ('JAPANESE_AFTER_CLOSE', '晚上22:30吃日料', 30.3252, 120.1505, 3, 'WAITING_RELAXATION', '', '{"cuisine":"日料","arrivalTime":"22:30"}', '营业时间硬约束零候选。'),
  ('WESTERN_BUDGET_TOO_LOW', '人均200以内的西餐', 30.3127, 120.1467, 3, 'WAITING_RELAXATION', '', '{"cuisine":"西餐","budgetPerPerson":200}', '西餐预算零候选。'),
  ('CANTONESE_BUDGET', '人均100的港式茶餐厅', 30.3161, 120.1492, 3, 'COMPLETED', '1', '{"cuisine":"港式","budgetPerPerson":100}', '菜系包含匹配与预算。'),
  ('WESTERN_DATE_HIGHER_BUDGET', '和女朋友约会吃西餐，人均350', 30.3127, 120.1467, 3, 'COMPLETED', '4', '{"cuisine":"西餐","budgetPerPerson":350,"occasion":"约会"}', '约会和西餐召回。'),
  ('HOT_POT_QUIET_BUDGET', '人均150吃安静的火锅', 30.3186, 120.1486, 3, 'COMPLETED', '6', '{"cuisine":"火锅","budgetPerPerson":150,"quiet":true}', '软偏好影响确定性重排。'),
  ('JAPANESE_BUDGET', '人均100吃日料', 30.3252, 120.1505, 3, 'COMPLETED', '8', '{"cuisine":"日料","budgetPerPerson":100}', '基础菜系与预算召回。'),
  ('HOT_POT_AVOID_QUEUE', '人均150火锅，不想排队', 30.3186, 120.1486, 3, 'COMPLETED', '6', '{"cuisine":"火锅","budgetPerPerson":150,"avoidQueue":true}', '排队偏好影响重排。'),
  ('NEARBY_JAPANESE_DEFAULT_RADIUS', '附近的日料，安静聊天', 30.3252, 120.1505, 3, 'COMPLETED', '8', '{"cuisine":"日料","nearby":true,"quiet":true}', '附近默认 3km 与安静偏好。'),
  ('HOT_POT_LATE_NIGHT', '晚上19:00吃火锅', 30.3186, 120.1486, 3, 'COMPLETED', '6', '{"cuisine":"火锅","arrivalTime":"19:00"}', '营业时间、菜系与排序。')
ON DUPLICATE KEY UPDATE
  `query_text` = VALUES(`query_text`), `latitude` = VALUES(`latitude`), `longitude` = VALUES(`longitude`),
  `max_candidates` = VALUES(`max_candidates`), `expected_status` = VALUES(`expected_status`),
  `expected_shop_ids` = VALUES(`expected_shop_ids`), `expected_constraints_json` = VALUES(`expected_constraints_json`),
  `notes` = VALUES(`notes`), `active` = 1;
