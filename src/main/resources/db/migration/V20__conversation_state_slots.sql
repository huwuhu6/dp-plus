ALTER TABLE `ai_chat_session`
  ADD COLUMN `version` int NOT NULL DEFAULT 1 AFTER `user_id`,
  ADD COLUMN `slots_json` json NULL AFTER `last_decision_session_id`;
