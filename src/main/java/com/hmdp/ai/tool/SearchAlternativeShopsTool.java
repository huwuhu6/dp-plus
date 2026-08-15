package com.hmdp.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SearchAlternativeShopsTool extends BaseAgentTool {
    @Resource private ShopMapper shopMapper;
    @Resource private AiShopProfileMapper profileMapper;

    @Override public String name() { return "search_alternative_shops"; }
    @Override public String description() { return "查询尚未展示的餐饮备选商户。用户问还有别的吗、换一家或想看其他选择时使用。"; }

    @Override public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("cuisine", property("string", "可选菜系，如日料、火锅、西餐；未知时传空字符串。"));
        return objectSchema(properties);
    }

    @Override public AgentToolResult execute(Map<String, Object> input, AgentSessionContext context) {
        String cuisine = input.get("cuisine") == null ? "" : String.valueOf(input.get("cuisine"));
        List<AiShopProfile> profiles = profileMapper.selectList(null);
        Map<Long, AiShopProfile> profileByShop = new LinkedHashMap<Long, AiShopProfile>();
        for (AiShopProfile profile : profiles) profileByShop.put(profile.getShopId(), profile);
        List<Shop> candidates = new ArrayList<Shop>();
        for (Shop shop : shopMapper.selectList(null)) {
            if (context.getShownShopIds().contains(shop.getId())) continue;
            AiShopProfile profile = profileByShop.get(shop.getId());
            if (!cuisine.trim().isEmpty() && (profile == null || profile.getCuisine() == null || !profile.getCuisine().contains(cuisine))) continue;
            candidates.add(shop);
        }
        candidates.sort(Comparator.comparing(Shop::getScore, Comparator.nullsLast(Comparator.reverseOrder())));
        if (candidates.size() > 3) candidates = candidates.subList(0, 3);
        StringBuilder text = new StringBuilder("可继续考虑：");
        List<Map<String, Object>> facts = new ArrayList<Map<String, Object>>();
        for (Shop shop : candidates) {
            context.getShownShopIds().add(shop.getId());
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("shopId", shop.getId()); item.put("shopName", shop.getName());
            item.put("avgPrice", shop.getAvgPrice()); item.put("score", shop.getScore());
            facts.add(item);
            text.append("\n- ").append(shop.getName()).append("，人均 ").append(shop.getAvgPrice())
                    .append(" 元，评分 ").append(shop.getScore() == null ? "暂无" : shop.getScore() / 10.0D);
        }
        AgentToolResult result = new AgentToolResult().summary("返回 " + candidates.size() + " 家未展示备选商户").displayText(text.toString());
        result.getFacts().put("shops", facts);
        return result;
    }
}
