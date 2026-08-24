-- No candidate under explicit constraints is a valid decision outcome, not an execution failure.
UPDATE `ai_conversation_evaluation_case`
SET `expected_final_status` = 'WAITING_RELAXATION',
    `notes` = CASE `case_code`
        WHEN 'START_NEW_RECOM_CLEAR_INTENT' THEN '用户明确切换为川菜新推荐；若福州当前位置无满足条件的候选，应进入 WAITING_RELAXATION 并展示可选放宽路径，不得回退至上一轮闽菜商户。'
        WHEN 'LOCATION_SWITCH_HANGZHOU_TO_FUZHOU' THEN '多轮中位置由杭州切换至福州；若福州当前坐标下无当地特色小吃候选，应进入 WAITING_RELAXATION 并展示可选放宽路径，且不得返回杭州商户。'
        WHEN 'CRITERIA_REFINEMENT_RETAINS_LOCATION' THEN '第二轮替换菜系并保留位置、预算与半径；若严格条件无候选，应进入 WAITING_RELAXATION 并展示可选放宽路径，而不是丢弃继承条件或伪造推荐。'
        ELSE `notes`
    END
WHERE `case_code` IN (
    'START_NEW_RECOM_CLEAR_INTENT',
    'LOCATION_SWITCH_HANGZHOU_TO_FUZHOU',
    'CRITERIA_REFINEMENT_RETAINS_LOCATION'
);
