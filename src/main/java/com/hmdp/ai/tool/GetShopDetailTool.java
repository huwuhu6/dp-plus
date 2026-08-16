package com.hmdp.ai.tool;

import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GetShopDetailTool extends BaseAgentTool {
    @Resource private ShopMapper shopMapper;

    @Override public String name() { return "get_shop_detail"; }
    @Override public String description() { return "查询某家商户的地址、人均、评分、营业时间等确定事实。用户问这家怎么样、几点关门或在哪里时使用。"; }
    @Override public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("shopId", property("integer", "商户 ID；用户说这家时可以省略，系统会使用当前聚焦商户。"));
        return objectSchema(properties);
    }
    @Override public AgentToolResult execute(Map<String, Object> input, AgentSessionContext context) {
        Long shopId = shopId(input, context);
        if (shopId == null) throw new IllegalArgumentException("当前没有可查询的商户，请先指定一家推荐商户");
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) throw new IllegalArgumentException("商户不存在");
        context.setFocusedShopId(shop.getId()); context.setFocusedShopName(shop.getName());
        String text = shop.getName() + "：人均 " + shop.getAvgPrice() + " 元，评分 "
                + (shop.getScore() == null ? "暂无" : shop.getScore() / 10.0D) + "，营业时间 " + shop.getOpenHours()
                + "，地址 " + shop.getAddress() + "。";
        AgentToolResult result = new AgentToolResult().summary("查询商户基础事实").displayText(text);
        result.setFocusedShopId(shop.getId()); result.setFocusedShopName(shop.getName());
        result.getFacts().put("shop", shop);
        return result;
    }
}
