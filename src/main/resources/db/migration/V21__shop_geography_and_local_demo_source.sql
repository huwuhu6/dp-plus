ALTER TABLE `tb_shop`
  ADD COLUMN `province` varchar(64) NULL AFTER `area`,
  ADD COLUMN `city` varchar(64) NULL AFTER `province`,
  ADD COLUMN `district` varchar(64) NULL AFTER `city`,
  ADD COLUMN `county` varchar(64) NULL AFTER `district`,
  ADD KEY `idx_tb_shop_geo_admin` (`province`, `city`, `district`, `county`);

UPDATE `ai_review_document`
SET `source_type` = 'LOCAL_DEMO'
WHERE `source_type` = 'SEED_DEMO';
