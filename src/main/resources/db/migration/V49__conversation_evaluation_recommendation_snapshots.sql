ALTER TABLE `tbl_ai_conversation_evaluation_case_result`
  ADD COLUMN `actual_recommendation_snapshots_json` json NULL AFTER `actual_tool_calls_json`;
