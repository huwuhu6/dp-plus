ALTER TABLE `ai_conversation_evaluation_case`
  ADD COLUMN `expected_tool_arguments_json` json NULL AFTER `expected_tool_names_json`;

ALTER TABLE `ai_conversation_evaluation_run`
  ADD COLUMN `tool_expected_count` int NOT NULL DEFAULT 0 AFTER `tool_matched_count`,
  ADD COLUMN `tool_covered_count` int NOT NULL DEFAULT 0 AFTER `tool_expected_count`;

ALTER TABLE `ai_conversation_evaluation_case_result`
  ADD COLUMN `actual_tool_calls_json` json NULL AFTER `actual_tool_names_json`,
  ADD COLUMN `expected_tool_count` int NOT NULL DEFAULT 0 AFTER `actual_tool_calls_json`,
  ADD COLUMN `covered_tool_count` int NOT NULL DEFAULT 0 AFTER `expected_tool_count`,
  ADD COLUMN `unexpected_tool_count` int NOT NULL DEFAULT 0 AFTER `covered_tool_count`,
  ADD COLUMN `tool_arguments_matched` tinyint(1) NULL AFTER `tool_matched`;
