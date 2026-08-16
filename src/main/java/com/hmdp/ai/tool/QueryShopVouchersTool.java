package com.hmdp.ai.tool;

import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QueryShopVouchersTool extends BaseAgentTool {
    @Resource private VoucherMapper voucherMapper;

    @Override public String name() { return "query_shop_vouchers"; }
    @Override public String description() { return "查询某家商户当前上架的普通券和秒杀券事实，包括支付金额、抵扣金额、规则、库存和有效期。只查询，不创建订单。"; }
    @Override public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("shopId", property("integer", "商户 ID；用户说这家时可以省略，系统会使用当前聚焦商户。"));
        return objectSchema(properties);
    }
    @Override public AgentToolResult execute(Map<String, Object> input, AgentSessionContext context) {
        Long shopId = shopId(input, context);
        if (shopId == null) throw new IllegalArgumentException("当前没有可查询优惠券的商户");
        List<Voucher> vouchers = voucherMapper.queryVoucherOfShop(shopId);
        StringBuilder text = new StringBuilder(vouchers.isEmpty() ? "当前没有查询到上架优惠券。" : "当前可用优惠券：");
        for (Voucher voucher : vouchers) {
            text.append("\n- ").append(voucher.getTitle()).append("：支付 ").append(voucher.getPayValue() / 100D)
                    .append(" 元，抵扣 ").append(voucher.getActualValue() / 100D).append(" 元；规则：")
                    .append(voucher.getRules() == null ? "未提供" : voucher.getRules().replace("\\n", "；"));
            if (voucher.getStock() != null) text.append("；秒杀库存 ").append(voucher.getStock());
        }
        AgentToolResult result = new AgentToolResult().summary("查询到 " + vouchers.size() + " 张上架优惠券").displayText(text.toString());
        result.getFacts().put("vouchers", vouchers);
        return result;
    }
}
