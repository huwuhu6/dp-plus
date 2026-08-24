UPDATE `ai_conversation_evaluation_case`
SET `expected_final_status` = 'WAITING_RELAXATION',
    `notes` = '用户从多人聚餐切换为一人快速火锅新需求。新任务必须替代旧候选；若杭州当前坐标下严格条件无候选，应进入 WAITING_RELAXATION 并给出显式放宽路径，不得返回旧聚餐候选。'
WHERE `case_code` = 'PAUSE_AND_SWITCH_TO_NEW_DEMAND';
