ALTER TABLE `tbl_shop_review`
  ADD COLUMN `revision` bigint unsigned NOT NULL DEFAULT 1 AFTER `status`;

ALTER TABLE `tbl_ai_review_document`
  MODIFY COLUMN `source_key` varchar(192) NOT NULL;

ALTER TABLE `tbl_ai_shop_profile`
  ADD COLUMN `input_revision` bigint unsigned NOT NULL DEFAULT 0 AFTER `summary`,
  ADD COLUMN `aggregated_revision` bigint unsigned NOT NULL DEFAULT 0 AFTER `input_revision`,
  ADD COLUMN `profile_status` varchar(16) NOT NULL DEFAULT 'READY' AFTER `aggregated_revision`,
  ADD KEY `idx_ai_shop_profile_rebuild` (`profile_status`, `update_time`);
