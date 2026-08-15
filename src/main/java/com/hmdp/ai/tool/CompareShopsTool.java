package com.hmdp.ai.tool;

import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CompareShopsTool extends BaseAgentTool {
    @Resource private ShopMapper shopMapper;

    @Override public String name() { return "compare_shops"; }
    @Override public String description() { return "比较两家商户的均价、评分、营业时间、地址和销量等事实。用户问两家哪个更合适时使用。"; }
    @Override public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("shopId", property("integer", "第一家商户 ID；用户说这家时可以省略。"));
        properties.put("otherShopId", property("integer", "第二家商户 ID，必须提供。"));
        return objectSchema(properties, "otherShopId");
    }
    @Override public AgentToolResult execute(Map<String, Object> input, AgentSessionContext context) {
        Long firstId = shopId(input, context);
        Object otherValue = input.get("otherShopId");
        if (firstId == null || otherValue == null) throw new IllegalArgumentException("比较商户需要两家商户 ID");
        Long secondId = otherValue instanceof Number ? ((Number) otherValue).longValue() : Long.valueOf(String.valueOf(otherValue));
        Shop first = shopMapper.selectById(firstId);
        Shop second = shopMapper.selectById(secondId);
        if (first == null || second == null) throw new IllegalArgumentException("比较的商户不存在");
        String text = "商户对比：\n- " + line(first) + "\n- " + line(second)
                + "\n以上为数据库事实；更适合与否需要结合你的预算、距离和场景偏好。";
        AgentToolResult result = new AgentToolResult().summary("完成两家商户的事实比较").displayText(text);
        result.getFacts().put("firstShop", first); result.getFacts().put("secondShop", second);
        return result;
    }
    private String line(Shop shop) {
        return shop.getName() + "：人均 " + shop.getAvgPrice() + " 元，评分 "
                + (shop.getScore() == null ? "暂无" : shop.getScore() / 10.0D) + "，营业时间 " + shop.getOpenHours();
    }
}
