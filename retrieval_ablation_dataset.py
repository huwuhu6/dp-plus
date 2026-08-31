#!/usr/bin/env python3
"""
Retrieval Ablation Dataset — Semantic Score Contribution to Ranking.

Ground Truth 标注规则：
  Grade 2: 满足全部 Hard Constraints，且强语义匹配（场景/环境/排队偏好命中）
  Grade 1: 满足全部 Hard Constraints，但语义匹配较弱/仅基础达标
  Grade 0: 违反任一 Hard Constraint 或明显无关

所有 expected_constraints 固定，不依赖模型提取。
Relaxation 类 Query 单独标记并排除出核心四项指标。

Hard Constraint 规则（与生产代码一致）：
  - budgetPerPerson > 0: avgPrice <= budgetPerPerson
  - radiusKm > 0: distance <= radiusKm
  - cuisine: exact match via CuisineCanonicalizer
"""
import json
import os
from dataclasses import dataclass, asdict
from typing import List


@dataclass
class GtShop:
    shop_id: int
    relevance: int   # 0 / 1 / 2
    note: str = ""


@dataclass
class AblationCase:
    case_id: str
    query: str
    latitude: float
    longitude: float
    city: str
    expected_constraints: dict
    ground_truth: List[GtShop]
    scenario: str
    notes: str = ""
    is_relaxation: bool = False


# ── 杭州坐标 ──
HZ_CENTER = (30.3127, 120.1467)
HZ_LAKE   = (30.3252, 120.1505)
# ── 福州坐标 ──
FZ_CANG_SHAN = (26.0456, 119.2734)
FZ_SHANGJIE  = (26.0789, 119.1945)
FZ_CENTER    = (26.0789, 119.2989)


def build_dataset() -> List[AblationCase]:
    cases = []

    # ========== 1. 结构化约束：菜系 + 预算 ==========
    # 杭州日料均价: 160~380. 预算 200 可覆盖 鳗诚屋(160)、小林(180)、大江户(195)
    cases.append(AblationCase(
        case_id="ABL_001",
        query="人均200以内的日料",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "日料", "budgetPerPerson": 200, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(22, 2, "小林刺身 180元 日料居酒屋，符合预算; 场景朋友聚餐/独自"),
            GtShop(24, 2, "鳗诚屋 160元 日料鳗鱼饭，符合预算; 安静"),
            GtShop(26, 2, "大江户日料 195元 日料自助，符合预算; 热闹"),
        ],
        scenario="结构化约束",
        notes="日料+预算200, 3家符合"
    ))

    # 港式茶餐厅: 敏华(90)✅, 太兴(125)❌超预算
    cases.append(AblationCase(
        case_id="ABL_002",
        query="人均100的港式茶餐厅",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "港式", "budgetPerPerson": 100, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(47, 2, "敏华冰厅 90元 港式茶餐厅，符合预算; 独自/热闹"),
        ],
        scenario="结构化约束",
        notes="港式+预算100, 仅敏华符合; 太兴125❌超预算"
    ))

    # ========== 2. 结构化+语义偏好（场景/环境） ==========
    cases.append(AblationCase(
        case_id="ABL_003",
        query="适合约会的安静西餐厅",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "西餐", "occasion": "约会", "quiet": True, "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(36, 2, "王品牛排 350元 约会+商务 安静+浪漫"),
            GtShop(37, 2, "西堤牛排 220元 约会+朋友聚餐 安静+浪漫"),
            GtShop(41, 2, "斗牛士牛排 185元 约会+商务 安静+包间"),
        ],
        scenario="结构化+语义偏好",
        notes="西餐+约会+安静, 3家强匹配"
    ))

    cases.append(AblationCase(
        case_id="ABL_004",
        query="安静的火锅，适合聊天",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "火锅", "quiet": True, "nearby": False, "radiusKm": -1, "budgetPerPerson": -1},
        ground_truth=[
            GtShop(30, 2, "凑凑火锅 165元 安静+浪漫，适合约会聊天"),
            GtShop(33, 2, "捞王锅物料理 155元 安静+包间，适合安静聊天"),
        ],
        scenario="结构化+语义偏好",
        notes="火锅+安静, 2家"
    ))

    # ========== 3. 仅菜系（无预算约束） ==========
    cases.append(AblationCase(
        case_id="ABL_005",
        query="日料",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "日料", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(20, 2, "鮨一日本料理 380元 高端日料"),
            GtShop(21, 2, "三上日本料理 220元 日料寿司"),
            GtShop(22, 2, "小林刺身 180元 日料居酒屋"),
            GtShop(23, 2, "七福神居酒屋 280元 日料烧鸟"),
            GtShop(24, 2, "鳗诚屋 160元 日料鳗鱼饭"),
            GtShop(25, 2, "和味亭日式料理 320元"),
            GtShop(26, 2, "大江户日料 195元 日料自助"),
            GtShop(27, 2, "樱屋日式烧肉 260元"),
        ],
        scenario="仅菜系",
        notes="全部8家杭州日料"
    ))

    cases.append(AblationCase(
        case_id="ABL_006",
        query="火锅",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "火锅", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(28, 2, "川味观火锅 125元"),
            GtShop(29, 2, "大渝火锅 135元"),
            GtShop(30, 2, "凑凑火锅 165元"),
            GtShop(31, 2, "蜀大侠火锅 145元"),
            GtShop(32, 2, "小龙坎老火锅 138元"),
            GtShop(33, 2, "捞王锅物料理 155元"),
            GtShop(34, 2, "德庄火锅 128元"),
            GtShop(35, 2, "巴奴毛肚火锅 150元"),
        ],
        scenario="仅菜系",
        notes="全部8家杭州火锅"
    ))

    # ========== 4. 地理 + 菜系 ==========
    cases.append(AblationCase(
        case_id="ABL_007",
        query="附近人均150的火锅",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "火锅", "budgetPerPerson": 150, "nearby": True, "radiusKm": 5},
        ground_truth=[
            GtShop(32, 2, "小龙坎 138元 城西银泰, 距中心3.3km, 符合预算+半径"),
        ],
        scenario="地理+菜系",
        notes="武林附近火锅+预算150+半径5km, 仅小龙坎3.3km符合"
    ))

    cases.append(AblationCase(
        case_id="ABL_008",
        query="西湖附近的日料",
        latitude=HZ_LAKE[0], longitude=HZ_LAKE[1],
        city="杭州",
        expected_constraints={"cuisine": "日料", "nearby": True, "radiusKm": 5, "budgetPerPerson": -1},
        ground_truth=[
            GtShop(24, 2, "鳗诚屋 160元 城西银泰, 距西湖约4.8km, 日料"),
        ],
        scenario="地理+菜系",
        notes="西湖附近日料+半径5km, 仅鳗诚屋4.8km符合"
    ))

    # ========== 5. 地名嵌入菜品名 ==========
    cases.append(AblationCase(
        case_id="ABL_009",
        query="重庆鸡公煲",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="",
        expected_constraints={"cuisine": "", "keyword": "重庆鸡公煲", "targetCity": "", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(29, 1, "大渝火锅 重庆火锅，语义相关但非鸡公煲"),
            GtShop(32, 1, "小龙坎 重庆火锅，语义相关"),
        ],
        scenario="地名嵌入菜品名",
        notes="重庆鸡公煲是菜品名，不提取城市；仅语义相关"
    ))

    # ========== 6. 无明显语义偏好 ==========
    cases.append(AblationCase(
        case_id="ABL_010",
        query="有什么好吃的",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[],  # 无约束，全量候选
        scenario="无明显语义偏好",
        notes="全量查询，排名完全由评分决定"
    ))

    # ========== 7. 烧烤 ==========
    cases.append(AblationCase(
        case_id="ABL_011",
        query="烧烤",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "烧烤", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(48, 2, "九田家黑牛烤肉 155元 烧烤"),
            GtShop(49, 2, "姜虎东白丁烤肉 175元 烧烤"),
            GtShop(50, 2, "聚十三烤肉 135元 烧烤"),
            GtShop(51, 2, "小串烧烤 85元 烧烤"),
            GtShop(52, 2, "高丽炉韩国烤肉 150元 烧烤"),
        ],
        scenario="仅菜系",
        notes="5家杭州烧烤"
    ))

    # ========== 8. 无语义证据（纯结构化） ==========
    cases.append(AblationCase(
        case_id="ABL_012",
        query="人均200的烧烤",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "烧烤", "budgetPerPerson": 200, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(48, 2, "九田家 155元 烧烤"),
            GtShop(49, 2, "姜虎东 175元 烧烤"),
            GtShop(50, 2, "聚十三 135元 烧烤"),
            GtShop(51, 2, "小串烧烤 85元 烧烤"),
            GtShop(52, 2, "高丽炉 150元 烧烤"),
        ],
        scenario="无语义证据",
        notes="烧烤+预算200, 5家全部符合; 纯结构化查询"
    ))

    # ========== 9. 福州数据 ==========
    cases.append(AblationCase(
        case_id="ABL_013",
        query="福州闽侯的日料",
        latitude=FZ_SHANGJIE[0], longitude=FZ_SHANGJIE[1],
        city="福州",
        expected_constraints={"cuisine": "日料", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(73, 2, "花潮日料 230元 仓山万达"),
            GtShop(74, 2, "筑地日本料理 165元 上街"),
            GtShop(75, 2, "一番居酒屋 140元 金山"),
            GtShop(76, 2, "樱之恋日料 190元 闽侯万家"),
        ],
        scenario="福州数据",
        notes="福州4家日料"
    ))

    cases.append(AblationCase(
        case_id="ABL_014",
        query="福州人均150的西餐",
        latitude=FZ_CANG_SHAN[0], longitude=FZ_CANG_SHAN[1],
        city="福州",
        expected_constraints={"cuisine": "西餐", "budgetPerPerson": 150, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(83, 2, "漫咖啡西餐 85元 符合预算"),
        ],
        scenario="福州数据",
        notes="福州西餐+预算150, 仅漫咖啡85元符合; 西堤195❌超预算"
    ))

    # ========== 10. 营业时间边界 ==========
    cases.append(AblationCase(
        case_id="ABL_015",
        query="晚上22:30吃火锅",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "火锅", "arrivalTime": "22:30", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(28, 2, "川味观 营业至02:00"),
            GtShop(30, 2, "凑凑火锅 营业至02:00"),
            GtShop(31, 2, "蜀大侠火锅 营业至00:00"),
            GtShop(35, 2, "巴奴毛肚火锅 营业至23:00"),
            GtShop(29, 2, "大渝火锅 营业至22:30"),
        ],
        scenario="营业时间边界",
        notes="22:30到店, 5家营业时间覆盖"
    ))

    # ========== 11. 预算严格 ==========
    cases.append(AblationCase(
        case_id="ABL_016",
        query="人均100的杭帮菜",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "杭帮菜", "budgetPerPerson": 100, "nearby": False, "radiusKm": -1},
        ground_truth=[],  # 杭儿风110❌, 杭州酒家120❌, 山外山185❌
        scenario="零结果",
        notes="杭帮菜预算100, 无符合; 零结果场景",
        is_relaxation=True
    ))

    # ========== 12. 烤肉 → 烧烤 canonicalization ==========
    cases.append(AblationCase(
        case_id="ABL_017",
        query="烤肉",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "烧烤", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(48, 2, "九田家黑牛烤肉 烧烤"),
            GtShop(49, 2, "姜虎东白丁烤肉 烧烤"),
            GtShop(50, 2, "聚十三烤肉 烧烤"),
            GtShop(52, 2, "高丽炉韩国烤肉 烧烤"),
        ],
        scenario="烤肉规范",
        notes="验证 烤肉→烧烤 canonicalization; 排除 51 小串烧烤(烤串)"
    ))

    # ========== 13. 牛排 → 西餐 canonicalization ==========
    cases.append(AblationCase(
        case_id="ABL_018",
        query="牛排",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "西餐", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(36, 2, "王品牛排 西餐"),
            GtShop(37, 2, "西堤牛排 西餐"),
            GtShop(40, 2, "菲兹牛排 西餐"),
            GtShop(41, 2, "斗牛士牛排 西餐"),
            GtShop(42, 2, "MR. STEAK 西餐"),
        ],
        scenario="牛排规范",
        notes="验证 牛排→西餐 canonicalization; 蓝蛙(美式)不一定是牛排"
    ))

    # ========== 14. 福州烧烤 ==========
    cases.append(AblationCase(
        case_id="ABL_019",
        query="福州烤肉",
        latitude=FZ_CANG_SHAN[0], longitude=FZ_CANG_SHAN[1],
        city="福州",
        expected_constraints={"cuisine": "烧烤", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(84, 2, "九田家黑牛烤肉 仓山万达"),
            GtShop(85, 2, "姜虎东白丁烤肉 上街"),
            GtShop(86, 2, "韩式炭火烤肉 闽侯万家"),
        ],
        scenario="福州数据",
        notes="福州3家烧烤"
    ))

    # ========== 15. Relaxation 零结果 ==========
    cases.append(AblationCase(
        case_id="ABL_020",
        query="人均10的日料",
        latitude=HZ_LAKE[0], longitude=HZ_LAKE[1],
        city="杭州",
        expected_constraints={"cuisine": "日料", "budgetPerPerson": 10, "nearby": False, "radiusKm": -1},
        ground_truth=[],
        scenario="零结果",
        notes="预算10元, 无候选",
        is_relaxation=True
    ))

    # ========== 16. 福州人均100以内火锅 ==========
    cases.append(AblationCase(
        case_id="ABL_021",
        query="福州人均100的火锅",
        latitude=FZ_CANG_SHAN[0], longitude=FZ_CANG_SHAN[1],
        city="福州",
        expected_constraints={"cuisine": "火锅", "budgetPerPerson": 100, "nearby": False, "radiusKm": -1},
        ground_truth=[],  # 朝天门110❌, 德庄130❌, 小龙坎135❌, 蜀九香120❌
        scenario="零结果",
        notes="福州火锅预算100, 无符合",
        is_relaxation=True
    ))

    # ========== 17. 杭州全城西餐无预算 ==========
    cases.append(AblationCase(
        case_id="ABL_022",
        query="杭州西餐",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "西餐", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(36, 2, "王品牛排 西餐"),
            GtShop(37, 2, "西堤牛排 西餐"),
            GtShop(38, 2, "J.S. Burgers 西餐汉堡"),
            GtShop(39, 2, "蓝蛙 西餐美式"),
            GtShop(40, 2, "菲兹牛排 西餐"),
            GtShop(41, 2, "斗牛士牛排 西餐"),
            GtShop(42, 2, "MR. STEAK 西餐"),
        ],
        scenario="仅菜系",
        notes="全部7家杭州西餐"
    ))

    # ========== 18. 安静+不排队+预算 ==========
    cases.append(AblationCase(
        case_id="ABL_023",
        query="找个安静不用排队的火锅，人均150以内",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "火锅", "quiet": True, "avoidQueue": True, "budgetPerPerson": 160, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(33, 2, "捞王 155元 安静+包间, 排队状况未知"),
        ],
        scenario="结构化+语义偏好",
        notes="火锅+安静+不排队+预算150, 仅捞王可能符合"
    ))

    # ========== 19. 福州西餐安静约会 ==========
    cases.append(AblationCase(
        case_id="ABL_024",
        query="福州适合约会的安静西餐厅",
        latitude=FZ_CANG_SHAN[0], longitude=FZ_CANG_SHAN[1],
        city="福州",
        expected_constraints={"cuisine": "西餐", "occasion": "约会", "quiet": True, "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(81, 2, "王品牛排 320元 仓山万达 约会+商务 安静+浪漫"),
            GtShop(82, 2, "西堤牛排 195元 台江万达 约会+朋友聚餐 安静+浪漫"),
        ],
        scenario="福州数据",
        notes="福州西餐+约会+安静, 2家"
    ))

    # ========== 20. 港式茶餐厅 → 港式 ==========
    cases.append(AblationCase(
        case_id="ABL_025",
        query="港式茶餐厅",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "港式", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(43, 2, "翠园 280元 港式粤菜"),
            GtShop(44, 2, "利苑酒家 350元 港式粤菜"),
            GtShop(45, 2, "稻香 155元 港式粤菜"),
            GtShop(46, 2, "太兴餐厅 125元 港式茶餐厅"),
            GtShop(47, 2, "敏华冰厅 90元 港式茶餐厅"),
        ],
        scenario="港式规范",
        notes="验证 港式茶餐厅→港式 canonicalization, 5家"
    ))

    # ========== 21. 福州闽菜 ==========
    cases.append(AblationCase(
        case_id="ABL_026",
        query="福州闽菜",
        latitude=FZ_CENTER[0], longitude=FZ_CENTER[1],
        city="福州",
        expected_constraints={"cuisine": "闽菜", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(60, 2, "聚春园闽菜馆 120元 闽菜海鲜"),
            GtShop(61, 2, "老福州海鲜 185元 闽菜海鲜"),
            GtShop(62, 2, "闽味轩 闽菜"),
            GtShop(63, 2, "海中鲜海鲜城 海鲜"),
            GtShop(64, 2, "福州大酒楼 闽菜"),
            GtShop(65, 2, "闽都海鲜舫 闽菜海鲜"),
            GtShop(66, 2, "榕城春 闽菜"),
            GtShop(67, 2, "潮江春海鲜 海鲜"),
        ],
        scenario="福州数据",
        notes="福州闽菜/海鲜, 8家"
    ))

    # ========== 22. 杭州人均100以内烧烤 ==========
    cases.append(AblationCase(
        case_id="ABL_027",
        query="人均100以内的烧烤",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "烧烤", "budgetPerPerson": 100, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(51, 2, "小串烧烤 85元 符合预算"),
        ],
        scenario="结构化约束",
        notes="烧烤+预算100, 仅小串85元符合"
    ))

    # ========== 23. 杭帮菜 ==========
    cases.append(AblationCase(
        case_id="ABL_028",
        query="杭帮菜",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "杭帮菜", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(53, 2, "杭州酒家 120元 杭帮菜"),
            GtShop(54, 2, "山外山菜馆 185元 杭帮菜"),
            GtShop(55, 2, "杭儿风 110元 杭帮菜"),
        ],
        scenario="仅菜系",
        notes="3家杭州杭帮菜"
    ))

    # ========== 24. 福州火锅预算150 ==========
    cases.append(AblationCase(
        case_id="ABL_029",
        query="福州人均150的火锅",
        latitude=FZ_CANG_SHAN[0], longitude=FZ_CANG_SHAN[1],
        city="福州",
        expected_constraints={"cuisine": "火锅", "budgetPerPerson": 150, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(77, 2, "朝天门火锅 110元 符合预算"),
            GtShop(78, 2, "德庄火锅 130元 符合预算"),
            GtShop(79, 2, "小龙坎火锅 135元 符合预算"),
            GtShop(80, 2, "蜀九香火锅 120元 符合预算"),
        ],
        scenario="福州数据",
        notes="福州4家火锅预算150以内"
    ))

    # ========== 25. 寿司 → 日料 ==========
    cases.append(AblationCase(
        case_id="ABL_030",
        query="寿司",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "日料", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(21, 2, "三上日本料理 日料寿司"),
        ],
        scenario="寿司规范",
        notes="验证 寿司→日料 canonicalization; 三上主营寿司"
    ))

    # ========== 26. 福州小吃 ==========
    cases.append(AblationCase(
        case_id="ABL_031",
        query="福州小吃",
        latitude=FZ_CENTER[0], longitude=FZ_CENTER[1],
        city="福州",
        expected_constraints={"cuisine": "福州小吃", "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(68, 2, "同利肉燕"),
            GtShop(69, 2, "永和鱼丸"),
            GtShop(70, 2, "耳聋伯元宵"),
            GtShop(71, 2, "达道牛肉"),
            GtShop(72, 2, "福屿海鲜锅边"),
        ],
        scenario="福州数据",
        notes="福州小吃5家"
    ))

    # ========== 27. 杭州预算200的西餐 ==========
    cases.append(AblationCase(
        case_id="ABL_032",
        query="人均200左右的西餐",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "西餐", "budgetPerPerson": 200, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(38, 2, "J.S. Burgers 95元 西餐"),
            GtShop(39, 2, "蓝蛙 145元 西餐"),
            GtShop(40, 2, "菲兹牛排 110元 西餐"),
            GtShop(41, 2, "斗牛士牛排 185元 西餐"),
            GtShop(42, 2, "MR. STEAK 170元 西餐"),
        ],
        scenario="结构化约束",
        notes="西餐+预算200, 5家符合; 西堤220❌, 王品350❌超预算"
    ))

    # ========== 28. 安静日料 ==========
    cases.append(AblationCase(
        case_id="ABL_033",
        query="找安静的日料店",
        latitude=HZ_CENTER[0], longitude=HZ_CENTER[1],
        city="杭州",
        expected_constraints={"cuisine": "日料", "quiet": True, "budgetPerPerson": -1, "nearby": False, "radiusKm": -1},
        ground_truth=[
            GtShop(20, 2, "鮨一日本料理 安静+景观"),
            GtShop(24, 2, "鳗诚屋 简约+安静"),
            GtShop(25, 2, "和味亭日式料理 安静+包间"),
        ],
        scenario="结构化+语义偏好",
        notes="日料+安静, 3家"
    ))

    # ========== 29. 杭州东站附近火锅 ==========
    cases.append(AblationCase(
        case_id="ABL_034",
        query="火车东站附近的火锅",
        latitude=30.2930, longitude=120.2100,  # 杭州东站
        city="杭州",
        expected_constraints={"cuisine": "火锅", "nearby": True, "radiusKm": 5, "budgetPerPerson": -1},
        ground_truth=[
            GtShop(35, 2, "巴奴毛肚火锅 庆春银泰, 距东站约4.7km"),
        ],
        scenario="地理+菜系",
        notes="东站附近火锅+半径5km, 仅巴奴4.7km符合"
    ))

    # ========== 30. 福州仓山万达附近 ==========
    cases.append(AblationCase(
        case_id="ABL_035",
        query="仓山万达附近有什么吃的",
        latitude=FZ_CANG_SHAN[0], longitude=FZ_CANG_SHAN[1],
        city="福州",
        expected_constraints={"cuisine": "", "nearby": True, "radiusKm": 1, "budgetPerPerson": -1},
        ground_truth=[
            GtShop(78, 1, "德庄火锅 130元 仓山万达内"),
            GtShop(81, 1, "王品牛排 320元 仓山万达内"),
            GtShop(73, 1, "花潮日料 230元 仓山万达内"),
            GtShop(84, 1, "九田家黑牛烤肉 150元 仓山万达附近"),
            GtShop(61, 1, "老福州海鲜 185元 仓山万达附近"),
        ],
        scenario="福州数据",
        notes="仓山万达附近1km, 5家"
    ))

    return cases


def dataset_stats(cases: List[AblationCase]) -> dict:
    active = [c for c in cases if not c.is_relaxation]
    relaxation = [c for c in cases if c.is_relaxation]
    scenarios = {}
    for c in cases:
        scenarios[c.scenario] = scenarios.get(c.scenario, 0) + 1
    gt_count = sum(len(c.ground_truth) for c in active)
    gt_grade2 = sum(1 for c in active for gt in c.ground_truth if gt.relevance == 2)
    gt_grade1 = sum(1 for c in active for gt in c.ground_truth if gt.relevance == 1)
    return {
        "total_cases": len(cases),
        "active_cases": len(active),
        "relaxation_cases": len(relaxation),
        "scenario_distribution": dict(sorted(scenarios.items())),
        "ground_truth_shops": gt_count,
        "grade_2_count": gt_grade2,
        "grade_1_count": gt_grade1,
    }


def to_json(cases: List[AblationCase]) -> str:
    data = []
    for c in cases:
        d = asdict(c)
        d["ground_truth"] = [asdict(gt) for gt in c.ground_truth]
        data.append(d)
    return json.dumps(data, ensure_ascii=False, indent=2)


def from_json(json_str: str) -> List[AblationCase]:
    data = json.loads(json_str)
    cases = []
    for d in data:
        gt = [GtShop(**g) for g in d["ground_truth"]]
        d.pop("ground_truth")
        cases.append(AblationCase(**d, ground_truth=gt))
    return cases


if __name__ == "__main__":
    cases = build_dataset()
    stats = dataset_stats(cases)
    print(f"数据集统计:")
    print(f"  总用例: {stats['total_cases']}")
    print(f"  活跃用例: {stats['active_cases']}")
    print(f"  Relaxation 用例: {stats['relaxation_cases']}")
    print(f"  Ground Truth 总数: {stats['ground_truth_shops']}")
    print(f"  Grade 2: {stats['grade_2_count']}, Grade 1: {stats['grade_1_count']}")
    print(f"\n场景分布:")
    for sc, cnt in stats['scenario_distribution'].items():
        print(f"  {sc}: {cnt}")

    output_path = os.path.join(os.path.dirname(__file__), "retrieval_ablation_dataset.json")
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(to_json(cases))
    print(f"\n已保存: {output_path}")