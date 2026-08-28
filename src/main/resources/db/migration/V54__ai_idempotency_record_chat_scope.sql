ALTER TABLE `tbl_ai_idempotency_record`
  ADD COLUMN `chat_id` varchar(64) NOT NULL DEFAULT '' AFTER `user_id`;

ALTER TABLE `tbl_ai_idempotency_record`
  DROP INDEX `uk_ai_idempotency_scope_user_key`,
  ADD UNIQUE KEY `uk_ai_idempotency_user_chat_scope_key` (`user_id`, `chat_id`, `scope`, `idempotency_key`);

-- Records created before this migration retain chat_id=''. They cannot replay into a named chat.
