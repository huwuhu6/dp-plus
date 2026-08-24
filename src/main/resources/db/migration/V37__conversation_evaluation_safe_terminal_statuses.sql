UPDATE `ai_conversation_evaluation_case`
SET `expected_final_status` = 'COMPLETED|WAITING_RELAXATION',
    `notes` = '用户从多人聚餐切换为一人简餐的新任务必须替代旧候选。若当前条件可召回商户则完成推荐；若无候选则进入 WAITING_RELAXATION 并提供显式放宽路径。两者均为允许的安全终态，不得回退到旧聚餐候选。'
WHERE `case_code` = 'PAUSE_AND_SWITCH_TO_NEW_DEMAND';
