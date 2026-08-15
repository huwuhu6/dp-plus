CREATE TABLE IF NOT EXISTS `ai_shop_profile` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `shop_id` bigint unsigned NOT NULL,
  `cuisine` varchar(64) NOT NULL DEFAULT '',
  `scene_tags` varchar(255) NOT NULL DEFAULT '',
  `ambience_tags` varchar(255) NOT NULL DEFAULT '',
  `queue_level` varchar(16) NOT NULL DEFAULT 'UNKNOWN',
  `summary` varchar(512) NOT NULL DEFAULT '',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_shop_profile_shop_id` (`shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_review_document` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `shop_id` bigint unsigned NOT NULL,
  `source_type` varchar(32) NOT NULL,
  `source_key` varchar(64) NOT NULL,
  `content` varchar(2048) NOT NULL,
  `tags` varchar(255) NOT NULL DEFAULT '',
  `sentiment` tinyint NOT NULL DEFAULT 0,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_review_source` (`source_key`),
  KEY `idx_ai_review_shop_id` (`shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_decision_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NULL,
  `query_text` varchar(1024) NOT NULL,
  `status` varchar(32) NOT NULL,
  `constraints_json` text NULL,
  `result_json` longtext NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_session_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_decision_step` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_id` bigint unsigned NOT NULL,
  `state` varchar(32) NOT NULL,
  `summary` varchar(512) NOT NULL,
  `duration_ms` bigint NOT NULL DEFAULT 0,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_step_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `ai_shop_profile` (`shop_id`, `cuisine`, `scene_tags`, `ambience_tags`, `queue_level`, `summary`) VALUES
  (1, '港式茶餐厅', '朋友聚餐,工作日简餐', '怀旧,热闹', 'MEDIUM', '平价港式茶餐厅，适合快速用餐。'),
  (2, '火锅,烤肉', '朋友聚餐,夜宵', '热闹', 'MEDIUM', '营业至深夜的北方风味餐厅。'),
  (3, '杭帮菜,中餐', '家庭聚餐,朋友聚餐', '明亮,热闹', 'HIGH', '商场内中餐，客流相对较大。'),
  (4, '西餐,牛排', '约会,纪念日', '安静,浪漫,花园', 'LOW', '适合约会的花园风格西餐厅。'),
  (5, '火锅', '朋友聚餐,家庭聚餐', '热闹', 'HIGH', '连锁火锅，服务稳定但高峰期客流较大。'),
  (6, '火锅', '朋友聚餐,家庭聚餐', '安静,传统', 'LOW', '传统铜锅风格，适合希望安静聊天的聚餐。'),
  (7, '烤鱼,川菜', '朋友聚餐', '热闹', 'MEDIUM', '商场内烤鱼餐厅。'),
  (8, '日料,寿司', '约会,朋友聚餐', '安静,简约', 'LOW', '寿司为主的日料店，适合轻量约会。'),
  (9, '火锅,羊蝎子', '朋友聚餐,夜宵', '热闹', 'MEDIUM', '北派炭火锅，适合多人聚餐。')
ON DUPLICATE KEY UPDATE
  `cuisine` = VALUES(`cuisine`), `scene_tags` = VALUES(`scene_tags`), `ambience_tags` = VALUES(`ambience_tags`),
  `queue_level` = VALUES(`queue_level`), `summary` = VALUES(`summary`);

-- SEED_DEMO 表示用于本地演示的人工整理证据，不应当被表述为真实线上评论。
INSERT INTO `ai_review_document` (`shop_id`, `source_type`, `source_key`, `content`, `tags`, `sentiment`) VALUES
  (4, 'SEED_DEMO', 'demo-shop-4-1', '花园风格布置，适合约会和拍照；晚餐时段更适合提前确认座位。', '约会,安静,环境', 1),
  (4, 'SEED_DEMO', 'demo-shop-4-2', '人均接近 300 元，适合纪念日等预算较高的场景。', '预算,西餐', 1),
  (6, 'SEED_DEMO', 'demo-shop-6-1', '传统铜锅氛围更偏聊天聚餐，非商场主通道位置相对安静。', '安静,聚餐', 1),
  (8, 'SEED_DEMO', 'demo-shop-8-1', '寿司和日料为主，人均在百元以内，适合两人轻量晚餐。', '日料,寿司,约会,预算', 1),
  (8, 'SEED_DEMO', 'demo-shop-8-2', '店内风格简约，适合希望安静聊天的用餐场景。', '安静,环境', 1),
  (1, 'BLOG', 'blog-shop-1-summary', '探店笔记提到港风环境与平价菜品，适合日常简餐。', '港式,平价', 1),
  (3, 'SEED_DEMO', 'demo-shop-3-1', '商场店客流相对集中，周末高峰期可能需要等位。', '排队,家庭聚餐', -1),
  (5, 'SEED_DEMO', 'demo-shop-5-1', '服务稳定，但热门时段需要把排队因素纳入决策。', '排队,火锅', 0)
ON DUPLICATE KEY UPDATE
  `content` = VALUES(`content`), `tags` = VALUES(`tags`), `sentiment` = VALUES(`sentiment`);
