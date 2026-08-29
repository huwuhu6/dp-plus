-- Canonical merchant-review facts. tbl_ai_review_document remains a derived AI retrieval projection.
CREATE TABLE `tbl_shop_review` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `shop_id` bigint unsigned NOT NULL,
  `source_type` varchar(32) NOT NULL,
  `source_key` varchar(128) NOT NULL,
  `user_id` bigint unsigned NULL,
  `rating` tinyint unsigned NULL,
  `content` varchar(2048) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop_review_source` (`source_type`, `source_key`),
  KEY `idx_shop_review_active` (`shop_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `tbl_ai_review_document`
  ADD COLUMN `source_review_id` bigint unsigned NULL AFTER `source_key`,
  ADD COLUMN `source_revision` bigint unsigned NULL AFTER `source_review_id`,
  ADD UNIQUE KEY `uk_ai_review_source_review` (`source_review_id`);
