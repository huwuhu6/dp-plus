ALTER TABLE `ai_chat_session`
  ADD COLUMN `working_memory_json` json NULL AFTER `slots_json`;
