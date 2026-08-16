-- General chat does not create a decision session and therefore has no final decision status.
UPDATE `ai_conversation_evaluation_case`
SET `expected_final_status` = NULL
WHERE `case_code` IN ('CHAT_IDENTITY_AND_CAPABILITIES', 'CHAT_WEATHER_NON_DINING_REJECT');

-- Distance questions about an already recommended shop are valid detail-tool follow-ups.
UPDATE `ai_conversation_evaluation_case`
SET `expected_tool_names_json` = '["get_shop_detail"]'
WHERE `case_code` IN ('GEO_NEARBY_RECOM_FUZHOU', 'GEO_NEARBY_RECOM_HANGZHOU');
