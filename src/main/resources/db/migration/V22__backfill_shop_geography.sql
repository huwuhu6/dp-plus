-- ==============================================================================
-- 增量迁移脚本：回填 tb_shop 表现有 80 家商户行政区字段
-- 字段范围：province, city, district, county
-- 目标商户 ID 范围：1-14, 20-55, 60-89
-- ==============================================================================

-- 1. 回填 浙江省杭州市 商户（ID: 1-14, 20-55）
UPDATE tb_shop
SET
    province = '浙江省',
    city = '杭州市',
    district = CASE id
        -- 拱墅区商户
                   WHEN 1  THEN '拱墅区' -- 110茶餐厅 (大关)
                   WHEN 2  THEN '拱墅区' -- 蔡馬洪涛烤肉 (拱宸桥/上塘)
                   WHEN 3  THEN '拱墅区' -- 新白鹿餐厅(运河上街店)
                   WHEN 4  THEN '拱墅区' -- Mamala(远洋乐堤港店)
                   WHEN 5  THEN '拱墅区' -- 海底捞火锅(水晶城店)
                   WHEN 6  THEN '拱墅区' -- 幸福里老北京涮锅(丝联店)
                   WHEN 7  THEN '拱墅区' -- 炉鱼(拱墅万达店)
                   WHEN 8  THEN '拱墅区' -- 浅草屋寿司(运河上街店)
                   WHEN 9  THEN '拱墅区' -- 羊老三火锅(运河上街店)
                   WHEN 10 THEN '拱墅区' -- 开乐迪KTV(运河上街店)
                   WHEN 11 THEN '拱墅区' -- INLOVE KTV(水晶城店)
                   WHEN 12 THEN '拱墅区' -- 魅(远洋乐堤港店)
                   WHEN 13 THEN '拱墅区' -- 讴K拉量贩KTV(北城天地店)
                   WHEN 14 THEN '拱墅区' -- 星聚会KTV(拱墅万达店)
                   WHEN 23 THEN '拱墅区' -- 七福神居酒屋(中山中路店)
                   WHEN 24 THEN '拱墅区' -- 鳗诚屋(城西银泰店)
                   WHEN 28 THEN '拱墅区' -- 川味观火锅(武林总店)
                   WHEN 32 THEN '拱墅区' -- 小龙坎老火锅(城西银泰店)
                   WHEN 38 THEN '拱墅区' -- J.S. Burgers(嘉里中心店)
                   WHEN 39 THEN '拱墅区' -- 蓝蛙(城西银泰店)
                   WHEN 43 THEN '拱墅区' -- 翠园(杭州大厦店)
                   WHEN 45 THEN '拱墅区' -- 稻香(城西银泰店)
                   WHEN 50 THEN '拱墅区' -- 聚十三烤肉(武林路店)
        -- 上城区商户
                   WHEN 20 THEN '上城区' -- 鮨一日本料理(平安金融中心)
                   WHEN 21 THEN '上城区' -- 三上日本料理(湖滨银泰in77)
                   WHEN 29 THEN '上城区' -- 大渝火锅(湖滨银泰in77)
                   WHEN 30 THEN '上城区' -- 凑凑火锅(来福士中心)
                   WHEN 35 THEN '上城区' -- 巴奴毛肚火锅(庆春银泰)
                   WHEN 36 THEN '上城区' -- 王品牛排(湖滨银泰in77)
                   WHEN 37 THEN '上城区' -- 西堤牛排(万象城)
                   WHEN 44 THEN '上城区' -- 利苑酒家(平安金融中心)
                   WHEN 46 THEN '上城区' -- 太兴餐厅(湖滨银泰in77)
                   WHEN 49 THEN '上城区' -- 姜虎东白丁烤肉(湖滨银泰in77)
                   WHEN 53 THEN '上城区' -- 杭州酒家(延安路店)
                   WHEN 55 THEN '上城区' -- 杭儿风(来福士中心)
        -- 滨江区商户
                   WHEN 22 THEN '滨江区' -- 小林刺身(滨江天街)
                   WHEN 31 THEN '滨江区' -- 蜀大侠火锅(星光大道)
                   WHEN 40 THEN '滨江区' -- 菲兹牛排(滨江龙湖天街)
                   WHEN 48 THEN '滨江区' -- 九田家黑牛烤肉(滨江宝龙城)
        -- 西湖区商户
                   WHEN 25 THEN '西湖区' -- 和味亭日式料理(黄龙饭店)
                   WHEN 41 THEN '西湖区' -- 斗牛士牛排(黄龙万科中心)
                   WHEN 47 THEN '西湖区' -- 敏华冰厅(西溪天街)
                   WHEN 51 THEN '西湖区' -- 小串烧烤(文一路店)
                   WHEN 54 THEN '西湖区' -- 山外山菜馆(北山路店)
        -- 钱塘区商户
                   WHEN 26 THEN '钱塘区' -- 大江户日料(下沙龙湖天街)
        -- 余杭区商户
                   WHEN 27 THEN '余杭区' -- 樱屋日式烧肉(余杭万达)
                   WHEN 33 THEN '余杭区' -- 捞王锅物料理(西溪印象城)
        -- 萧山区商户
                   WHEN 34 THEN '萧山区' -- 德庄火锅(萧山万象汇)
                   WHEN 52 THEN '萧山区' -- 高丽炉韩国烤肉(萧山银隆)
        -- 临平区商户
                   WHEN 42 THEN '临平区' -- MR. STEAK(临平银泰城)
                   ELSE district
        END,
    county = NULL
WHERE (id BETWEEN 1 AND 14) OR (id BETWEEN 20 AND 55);

-- 2. 回填 福建省福州市/闽侯县 商户（ID: 60-89）
UPDATE tb_shop
SET
    province = '福建省',
    city = '福州市',
    district = CASE id
        -- 鼓楼区商户
                   WHEN 66 THEN '鼓楼区' -- 榕城春(东街口)
                   WHEN 69 THEN '鼓楼区' -- 永和鱼丸(南后街)
                   WHEN 72 THEN '鼓楼区' -- 福屿海鲜锅边(福屿路)
        -- 台江区商户
                   WHEN 64 THEN '台江区' -- 福州大酒楼(台江万达)
                   WHEN 68 THEN '台江区' -- 同利肉燕(上下杭)
                   WHEN 70 THEN '台江区' -- 耳聋伯元宵(苍霞)
                   WHEN 71 THEN '台江区' -- 达道牛肉(达道路)
                   WHEN 79 THEN '台江区' -- 小龙坎火锅(台江万达)
                   WHEN 82 THEN '台江区' -- 西堤牛排(台江万达)
        -- 仓山区商户
                   WHEN 61 THEN '仓山区' -- 老福州海鲜酒楼(仓山万达)
                   WHEN 63 THEN '仓山区' -- 海中鲜海鲜城(金山金榕北路)
                   WHEN 73 THEN '仓山区' -- 花潮日料(仓山万达)
                   WHEN 75 THEN '仓山区' -- 一番居酒屋(金山金洲南路)
                   WHEN 78 THEN '仓山区' -- 德庄火锅(仓山万达)
                   WHEN 81 THEN '仓山区' -- 王品牛排(仓山万达)
                   WHEN 83 THEN '仓山区' -- 漫咖啡西餐(金山万科里)
                   WHEN 84 THEN '仓山区' -- 九田家黑牛烤肉(仓山万达)
                   WHEN 87 THEN '仓山区' -- 大丰收脆鱼(仓山万达)
        -- 闽侯县商户
                   WHEN 60 THEN '闽侯县' -- 聚春园闽菜馆(上街永嘉天地)
                   WHEN 62 THEN '闽侯县' -- 闽味轩(南屿镇)
                   WHEN 65 THEN '闽侯县' -- 闽都海鲜舫(甘蔗街道)
                   WHEN 67 THEN '闽侯县' -- 潮江春海鲜(甘蔗滨江大道)
                   WHEN 74 THEN '闽侯县' -- 筑地日本料理(上街学府南路)
                   WHEN 76 THEN '闽侯县' -- 樱之恋日料(甘蔗万家广场)
                   WHEN 77 THEN '闽侯县' -- 朝天门火锅(上街国宾大道)
                   WHEN 80 THEN '闽侯县' -- 蜀九香火锅(甘蔗街心路)
                   WHEN 85 THEN '闽侯县' -- 姜虎东白丁烤肉(上街学府路)
                   WHEN 86 THEN '闽侯县' -- 韩式炭火烤肉(甘蔗万家广场)
                   WHEN 88 THEN '闽侯县' -- 粤式茶点(甘蔗滨江路)
                   WHEN 89 THEN '闽侯县' -- 闽师东北菜(上街广贤路)
                   ELSE district
        END,
    county = CASE
                 WHEN id IN (60, 62, 65, 67, 74, 76, 77, 80, 85, 86, 88, 89) THEN '闽侯县'
                 ELSE NULL
        END
WHERE id BETWEEN 60 AND 89;

-- 3. 校验回填结果
SELECT
    province,
    city,
    district,
    county,
    COUNT(*) AS shop_count
FROM tb_shop
WHERE (id BETWEEN 1 AND 14) OR (id BETWEEN 20 AND 55) OR (id BETWEEN 60 AND 89)
GROUP BY province, city, district, county
ORDER BY province DESC, city, district;