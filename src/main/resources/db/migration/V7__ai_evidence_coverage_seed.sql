-- 补齐商户 2 的演示证据，来源明确标记为 SEED_DEMO，不代表真实线上评论。
INSERT INTO `ai_review_document` (`shop_id`, `source_type`, `source_key`, `content`, `tags`, `sentiment`) VALUES
  (2, 'SEED_DEMO', 'demo-shop-2-1', '铜锅涮羊肉营业至凌晨，适合晚间多人聚餐；高峰期仍建议提前确认到店安排。', '火锅,夜宵,营业时间,聚餐', 1)
ON DUPLICATE KEY UPDATE
  `content` = VALUES(`content`), `tags` = VALUES(`tags`), `sentiment` = VALUES(`sentiment`);
