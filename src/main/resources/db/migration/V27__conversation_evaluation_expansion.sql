-- ==============================================================================
-- 评测集数据插入脚本：ai_conversation_evaluation_case (20条多轮餐饮决策用例)
-- 包含 10 个核心场景类别，每类 2 条（覆盖 conversation-v1 与 conversation-holdout-v1）
-- ==============================================================================

INSERT INTO ai_conversation_evaluation_case (
    case_code,
    dataset_version,
    turns_json,
    expected_routes_json,
    expected_tool_names_json,
    expected_final_status,
    expected_shop_ids,
    expected_city,
    notes
) VALUES
-- ------------------------------------------------------------------------------
-- 场景 1：身份/能力闲聊与非餐饮拒答
-- ------------------------------------------------------------------------------
(
    'CHAT_IDENTITY_AND_CAPABILITIES',
    'conversation-v1',
    '[{"message": "你是谁？能帮我做什么？"}, {"message": "你能帮我写一篇计算机期末论文吗？"}]',
    '["GENERAL_CHAT", "GENERAL_CHAT"]',
    '[]',
    'COMPLETED',
    '',
    NULL,
    '测试Agent自我身份说明以及对非餐饮类需求（论文写作）的明确拒答与能力边界引导'
),
(
    'CHAT_WEATHER_NON_DINING_REJECT',
    'conversation-holdout-v1',
    '[{"message": "今天福州天气怎么样？"}, {"message": "帮我买一张去厦门的动车票"}]',
    '["GENERAL_CHAT", "GENERAL_CHAT"]',
    '[]',
    'COMPLETED',
    '',
    NULL,
    '测试天气闲聊及票务代订等非餐饮消费任务的拒答，引导回餐饮推荐'
),

-- ------------------------------------------------------------------------------
-- 场景 2：浏览器定位后附近餐饮推荐
-- ------------------------------------------------------------------------------
(
    'GEO_NEARBY_RECOM_FUZHOU',
    'conversation-v1',
    '[{"message": "我饿了，附近有什么好吃的闽菜？", "location": {"latitude": 26.0789, "longitude": 119.1945}}, {"message": "推荐的这几家走过去大概多远？", "location": {"latitude": 26.0789, "longitude": 119.1945}}]',
    '["START_DECISION", "BUSINESS_FOLLOW_UP"]',
    '["search_alternative_shops", "get_shop_detail"]',
    'COMPLETED',
    '',
    '福州市',
    '福州GPS定位场景下的就近闽菜推荐及距离追问'
),
(
    'GEO_NEARBY_RECOM_HANGZHOU',
    'conversation-holdout-v1',
    '[{"message": "推荐附近评分高的火锅店", "location": {"latitude": 30.2741, "longitude": 120.1551}}, {"message": "人均大概多少钱？", "location": {"latitude": 30.2741, "longitude": 120.1551}}]',
    '["START_DECISION", "BUSINESS_FOLLOW_UP"]',
    '["search_alternative_shops", "get_shop_detail"]',
    'COMPLETED',
    '',
    '杭州市',
    '杭州GPS定位场景下的附近火锅推荐及人均消费追问'
),

-- ------------------------------------------------------------------------------
-- 场景 3：推荐后询问评价、优惠券、营业时间
-- ------------------------------------------------------------------------------
(
    'FOLLOW_UP_VOUCHER_AND_EVIDENCE',
    'conversation-v1',
    '[{"message": "推荐一家附近的日料店", "location": {"latitude": 30.2741, "longitude": 120.1551}}, {"message": "这家店现在有代金券或者团购套餐吗？", "location": {"latitude": 30.2741, "longitude": 120.1551}}, {"message": "大家评价刺身新鲜吗？", "location": {"latitude": 30.2741, "longitude": 120.1551}}]',
    '["START_DECISION", "BUSINESS_FOLLOW_UP", "BUSINESS_FOLLOW_UP"]',
    '["search_alternative_shops", "get_shop_detail", "query_shop_vouchers", "search_shop_evidence"]',
    'COMPLETED',
    '',
    '杭州市',
    '推荐商户后连续追问优惠券及菜品评价佐证'
),
(
    'FOLLOW_UP_HOURS_AND_REVIEWS',
    'conversation-holdout-v1',
    '[{"message": "附近有什么好喝的下午茶甜品店？", "location": {"latitude": 26.0789, "longitude": 119.1945}}, {"message": "这家店几点打烊？现在去还营业吗？", "location": {"latitude": 26.0789, "longitude": 119.1945}}, {"message": "招牌甜点大家评价怎么样？", "location": {"latitude": 26.0789, "longitude": 119.1945}}]',
    '["START_DECISION", "BUSINESS_FOLLOW_UP", "BUSINESS_FOLLOW_UP"]',
    '["search_alternative_shops", "get_shop_detail", "search_shop_evidence"]',
    'COMPLETED',
    '',
    '福州市',
    '推荐商户后追问营业时间状态与真实口碑评价'
),

-- ------------------------------------------------------------------------------
-- 场景 4：候选店铺指代：这家、那家、这个日本料理
-- ------------------------------------------------------------------------------
(
    'ANAPHORA_RESOLUTION_THIS_THAT',
    'conversation-v1',
    '[{"message": "附近推荐两家不同风格的餐厅", "location": {"latitude": 30.2741, "longitude": 120.1551}}, {"message": "第一家那个日本料理环境怎么样？", "location": {"latitude": 30.2741, "longitude": 120.1551}}, {"message": "那另一家店有打折券吗？", "location": {"latitude": 30.2741, "longitude": 120.1551}}]',
    '["START_DECISION", "BUSINESS_FOLLOW_UP", "BUSINESS_FOLLOW_UP"]',
    '["search_alternative_shops", "get_shop_detail", "search_shop_evidence", "query_shop_vouchers"]',
    'COMPLETED',
    '',
    '杭州市',
    '多候选商户指代消歧（品类指代“那个日本料理”、位置指代“另一家店”）'
),
(
    'ANAPHORA_CUISINE_ALIAS_REFERENCE',
    'conversation-holdout-v1',
    '[{"message": "推荐几家好吃的肉类正餐", "location": {"latitude": 26.0789, "longitude": 119.1945}}, {"message": "那家烤肉店评分如何？", "location": {"latitude": 26.0789, "longitude": 119.1945}}, {"message": "这家有代金券可以用吗？", "location": {"latitude": 26.0789, "longitude": 119.1945}}]',
    '["START_DECISION", "BUSINESS_FOLLOW_UP", "BUSINESS_FOLLOW_UP"]',
    '["search_alternative_shops", "get_shop_detail", "query_shop_vouchers"]',
    'COMPLETED',
    '',
    '福州市',
    '通过菜系指代（“那家烤肉店”）及代词承接（“这家”）完成连续商户追问'
),

-- ------------------------------------------------------------------------------
-- 场景 5：明确发起新推荐，不应被误判为旧商户追问
-- ------------------------------------------------------------------------------
(
    'START_NEW_RECOM_CLEAR_INTENT',
    'conversation-v1',
    '[{"message": "推荐福州的佛跳墙餐厅", "location": {"latitude": 26.0789, "longitude": 119.1945}}, {"message": "算了不想吃闽菜了，重新帮我找一家川菜馆", "location": {"latitude": 26.0789, "longitude": 119.1945}}]',
    '["START_DECISION", "START_DECISION"]',
    '["search_alternative_shops", "get_shop_detail"]',
    'COMPLETED',
    '',
    '福州市',
    '用户明确放弃当前推荐重新发起新推荐请求，不得误判为旧商户追问'
),
(
    'START_NEW_RECOM_AFTER_VOUCHER',
    'conversation-holdout-v1',
    '[{"message": "附近有没有吃牛排的地方？", "location": {"latitude": 30.2741, "longitude": 120.1551}}, {"message": "这家有优惠券吗？", "location": {"latitude": 30.2741, "longitude": 120.1551}}, {"message": "人均太贵了，重新推荐附近便宜点的面馆吧", "location": {"latitude": 30.2741, "longitude": 120.1551}}]',
    '["START_DECISION", "BUSINESS_FOLLOW_UP", "START_DECISION"]',
    '["search_alternative_shops", "get_shop_detail", "query_shop_vouchers"]',
    'COMPLETED',
    '',
    '杭州市',
    '在追问优惠券后由于价格不符重新发起新类目推荐，正确识别START_DECISION'
),

-- ------------------------------------------------------------------------------
-- 场景 6：拒绝定位后全城搜索
-- ------------------------------------------------------------------------------
(
    'LOCATION_REJECTED_CITYWIDE_SEARCH_FZ',
    'conversation-v1',
    '[{"message": "我想吃福州鱼丸"}, {"message": "不想提供定位，直接帮我找福州全城最好吃的鱼丸店"}]',
    '["LOCATION_RESOLUTION", "START_DECISION"]',
    '["search_alternative_shops", "get_shop_detail"]',
    'COMPLETED',
    '',
    '福州市',
    '未提供GPS定位触发位置确认，用户拒绝提供精确坐标后降级为城市范围搜索'
),
(
    'LOCATION_REJECTED_CITYWIDE_SEARCH_HZ',
    'conversation-holdout-v1',
    '[{"message": "推荐几家老字号杭帮菜"}, {"message": "不给定位权限，给我看杭州市区评分最高的热门店就行"}]',
    '["LOCATION_RESOLUTION", "START_DECISION"]',
    '["search_alternative_shops", "get_shop_detail"]',
    'COMPLETED',
    '',
    '杭州市',
    '拒绝授权浏览器定位后按明确的城市名进行全城范围热门推荐'
),

-- ------------------------------------------------------------------------------
-- 场景 7：无结果后扩大距离或放宽菜系
-- ------------------------------------------------------------------------------
(
    'NO_RESULT_EXPAND_DISTANCE',
    'conversation-v1',
    '[{"message": "附近500米内有没有正宗西班牙海鲜饭？", "location": {"latitude": 26.0789, "longitude": 119.1945}}, {"message": "扩大范围找找", "location": {"latitude": 26.0789, "longitude": 119.1945}, "selectedOptionId": "EXPAND_SEARCH_RADIUS"}]',
    '["START_DECISION", "DECISION_EVENT"]',
    '["search_alternative_shops", "get_shop_detail"]',
    'COMPLETED',
    '',
    '福州市',
    '严格距离无召回结果时，点击前端按钮事件扩大搜索半径召回商户'
),
(
    'NO_RESULT_RELAX_CUISINE_CATEGORY',
    'conversation-holdout-v1',
    '[{"message": "附近有正宗墨西哥Taco店吗？", "location": {"latitude": 30.2741, "longitude": 120.1551}}, {"message": "既然没有Taco，那放宽到美式西餐或者拉美风味都行", "location": {"latitude": 30.2741, "longitude": 120.1551}}]',
    '["START_DECISION", "START_DECISION"]',
    '["search_alternative_shops", "get_shop_detail"]',
    'COMPLETED',
    '',
    '杭州市',
    '极小众品类无召回结果后，主动放宽菜系标签发起新的推荐查询'
),

-- ------------------------------------------------------------------------------
-- 场景 8：暂停推荐后改问新餐饮需求
-- ------------------------------------------------------------------------------
(
    'PAUSE_AND_SWITCH_TO_NEW_DEMAND',
    'conversation-v1',
    '[{"message": "推荐附近适合聚餐的大桌火锅", "location": {"latitude": 30.2741, "longitude": 120.1551}}, {"message": "聚会先取消了，我自己一个人吃点简餐快餐就行", "location": {"latitude": 30.2741, "longitude": 120.1551}}]',
    '["START_DECISION", "START_DECISION"]',
    '["search_alternative_shops", "get_shop_detail"]',
    'COMPLETED',
    '',
    '杭州市',
    '中断多人聚餐决策流程，上下文切换为单人快餐推荐需求'
),
(
    'PAUSE_INTERRUPTED_BY_COFFEE',
    'conversation-holdout-v1',
    '[{"message": "帮我找一下附近的粤式茶餐厅", "location": {"latitude": 26.0789, "longitude": 119.1945}}, {"message": "等等，我现在还不饿，先推荐附近评分高的精品咖啡店", "location": {"latitude": 26.0789, "longitude": 119.1945}}]',
    '["START_DECISION", "START_DECISION"]',
    '["search_alternative_shops", "get_shop_detail"]',
    'COMPLETED',
    '',
    '福州市',
    '正餐推荐决策过程中断，切换为咖啡饮品类即时决策'
),

-- ------------------------------------------------------------------------------
-- 场景 9：福州与杭州位置切换，最终推荐不得跨城
-- ------------------------------------------------------------------------------
(
    'LOCATION_SWITCH_HANGZHOU_TO_FUZHOU',
    'conversation-v1',
    '[{"message": "我在杭州想吃火锅", "location": {"latitude": 30.2741, "longitude": 120.1551}}, {"message": "我刚飞到福州了，按我现在位置重新推荐福州当地特色小吃", "location": {"latitude": 26.0789, "longitude": 119.1945}}]',
    '["START_DECISION", "START_DECISION"]',
    '["search_alternative_shops", "get_shop_detail"]',
    'COMPLETED',
    '',
    '福州市',
    '多轮中地理位置由杭州切换至福州，验证最终推荐结果严格锁定福州市商户'
),
(
    'LOCATION_SWITCH_FUZHOU_TO_HANGZHOU',
    'conversation-holdout-v1',
    '[{"message": "推荐福州三坊七巷附近的闽菜", "location": {"latitude": 26.0789, "longitude": 119.1945}}, {"message": "改行程去杭州出差了，推荐杭州东站附近的美食", "location": {"latitude": 30.2741, "longitude": 120.1551}}]',
    '["START_DECISION", "START_DECISION"]',
    '["search_alternative_shops", "get_shop_detail"]',
    'COMPLETED',
    '',
    '杭州市',
    '多轮中地理位置由福州切换至杭州，验证最终推荐结果无跨城污染'
),

-- ------------------------------------------------------------------------------
-- 场景 10：结束推荐后恢复对已推荐商户的追问
-- ------------------------------------------------------------------------------
(
    'RESUME_FOLLOW_UP_AFTER_END_DECISION',
    'conversation-v1',
    '[{"message": "推荐附近的海鲜酒楼", "location": {"latitude": 26.0789, "longitude": 119.1945}}, {"message": "就决定是这家了，谢谢！", "location": {"latitude": 26.0789, "longitude": 119.1945}, "selectedOptionId": "FINISH_DECISION"}, {"message": "对了，刚选的那家店支持包厢预订或者有大桌吗？", "location": {"latitude": 26.0789, "longitude": 119.1945}}]',
    '["START_DECISION", "DECISION_EVENT", "BUSINESS_FOLLOW_UP"]',
    '["search_alternative_shops", "get_shop_detail", "search_shop_evidence"]',
    'COMPLETED',
    '',
    '福州市',
    '完成决策闭环后用户再次唤醒对选定商户的细节追问（包厢/设施）'
),
(
    'RESUME_FOLLOW_UP_AFTER_DECISION_CONFIRM',
    'conversation-holdout-v1',
    '[{"message": "附近有什么好吃的烤肉店？", "location": {"latitude": 30.2741, "longitude": 120.1551}}, {"message": "好的，就去吃第一家", "location": {"latitude": 30.2741, "longitude": 120.1551}, "selectedOptionId": "CONFIRM_SHOP"}, {"message": "刚才推荐的那家烤肉店现在有优惠券可以领吗？", "location": {"latitude": 30.2741, "longitude": 120.1551}}]',
    '["START_DECISION", "DECISION_EVENT", "BUSINESS_FOLLOW_UP"]',
    '["search_alternative_shops", "get_shop_detail", "query_shop_vouchers"]',
    'COMPLETED',
    '',
    '杭州市',
    '点击商户确认事件结束决策后，继续追问已锁定商户的优惠券'
)
ON DUPLICATE KEY UPDATE
    dataset_version          = VALUES(dataset_version),
    turns_json               = VALUES(turns_json),
    expected_routes_json     = VALUES(expected_routes_json),
    expected_tool_names_json = VALUES(expected_tool_names_json),
    expected_final_status    = VALUES(expected_final_status),
    expected_shop_ids        = VALUES(expected_shop_ids),
    expected_city            = VALUES(expected_city),
    notes                    = VALUES(notes);

-- The recommendation state machine does not use Agent tools during initial retrieval.
-- Tool expectations below only cover explicitly supported business follow-up turns.
UPDATE ai_conversation_evaluation_case
SET expected_tool_names_json = '[]'
WHERE case_code IN (
    'CHAT_IDENTITY_AND_CAPABILITIES', 'CHAT_WEATHER_NON_DINING_REJECT',
    'GEO_NEARBY_RECOM_FUZHOU', 'GEO_NEARBY_RECOM_HANGZHOU',
    'START_NEW_RECOM_CLEAR_INTENT', 'NO_RESULT_RELAX_CUISINE_CATEGORY',
    'PAUSE_AND_SWITCH_TO_NEW_DEMAND', 'PAUSE_INTERRUPTED_BY_COFFEE',
    'LOCATION_SWITCH_HANGZHOU_TO_FUZHOU', 'LOCATION_SWITCH_FUZHOU_TO_HANGZHOU'
);

UPDATE ai_conversation_evaluation_case
SET expected_tool_names_json = '["query_shop_vouchers","search_shop_evidence"]'
WHERE case_code = 'FOLLOW_UP_VOUCHER_AND_EVIDENCE';
UPDATE ai_conversation_evaluation_case
SET expected_tool_names_json = '["get_shop_detail","search_shop_evidence"]'
WHERE case_code = 'FOLLOW_UP_HOURS_AND_REVIEWS';
UPDATE ai_conversation_evaluation_case
SET expected_tool_names_json = '["search_shop_evidence","query_shop_vouchers"]'
WHERE case_code IN ('ANAPHORA_RESOLUTION_THIS_THAT', 'ANAPHORA_CUISINE_ALIAS_REFERENCE');
UPDATE ai_conversation_evaluation_case
SET expected_tool_names_json = '["query_shop_vouchers"]'
WHERE case_code = 'START_NEW_RECOM_AFTER_VOUCHER';

-- These generated trajectories require unsupported button IDs or an unimplemented shop-confirmation event.
-- Keep their data for later completion, but do not count them as current regression cases.
UPDATE ai_conversation_evaluation_case SET active = 0
WHERE case_code IN (
    'LOCATION_REJECTED_CITYWIDE_SEARCH_FZ', 'LOCATION_REJECTED_CITYWIDE_SEARCH_HZ',
    'NO_RESULT_EXPAND_DISTANCE', 'RESUME_FOLLOW_UP_AFTER_END_DECISION',
    'RESUME_FOLLOW_UP_AFTER_DECISION_CONFIRM'
);
