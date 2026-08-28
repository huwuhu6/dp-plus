package com.hmdp.ai.service;

import com.hmdp.ai.client.QueryRewriteClient;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.DecisionRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rewrites elliptical business follow-ups without letting the model own conversation state. */
@Service
public class ConversationContextRewriter {
    private static final Logger log = LoggerFactory.getLogger(ConversationContextRewriter.class);
    @Resource private QueryRewriteClient queryRewriteClient;

    public ContextRewriteResult rewrite(String query, List<Map<String, Object>> history, AgentSessionContext context) {
        return rewrite(query, history, context, false);
    }

    /**
     * Location scope changes are deterministic search continuations, even when a previous
     * attempt returned no candidates and therefore cannot provide a shop reference table.
     */
    public ContextRewriteResult rewrite(String query, List<Map<String, Object>> history, AgentSessionContext context,
                                        boolean allowSearchContinuation) {
        if (query == null || query.trim().isEmpty()) return ContextRewriteResult.unchanged(query, "EMPTY_QUERY");
        if (allowSearchContinuation && refersToCurrentDeviceLocation(query)) {
            ContextRewriteResult result = new ContextRewriteResult();
            result.setOriginalQuery(query);
            result.setRewrittenQuery("在当前设备附近搜索餐饮商户");
            result.setApplied(true);
            result.setUsedModel(false);
            result.setReason("CURRENT_DEVICE_LOCATION_CONTINUATION");
            return result;
        }
        if (!hasBusinessContext(context)) {
            return ContextRewriteResult.unchanged(query, "NO_WORKING_MEMORY");
        }
        if (!needsRewrite(query)) return ContextRewriteResult.unchanged(query, "SELF_CONTAINED");
        if (!queryRewriteClient.isConfigured()) return ContextRewriteResult.unchanged(query, "MODEL_UNAVAILABLE");
        try {
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            messages.add(message("system", "你是餐饮 Agent 的上下文与指代解析器。只根据工作记忆补全省略、指代、地点和继承条件，输出一条下游可执行的自包含中文查询。若问第一家/第二家，展开为候选列表对应商户；若说这家/那家，展开为当前聚焦商户。只输出改写后的查询，不判断业务意图、不选择 Chat Action、不决定 Decision 状态、不回答问题、不编造商户或事实。"));
            messages.add(message("system", "工作记忆：" + workingMemory(context)));
            if (history != null && !history.isEmpty()) {
                messages.add(message("system", "最近对话：" + compactHistory(history)));
            }
            messages.add(message("user", query));
            String rewritten = queryRewriteClient.rewrite(messages).trim();
            if (rewritten.isEmpty() || rewritten.length() > 300) {
                return ContextRewriteResult.unchanged(query, "INVALID_MODEL_OUTPUT");
            }
            ContextRewriteResult result = new ContextRewriteResult();
            result.setOriginalQuery(query);
            result.setRewrittenQuery(rewritten);
            result.setApplied(!query.equals(rewritten));
            result.setUsedModel(true);
            result.setReason("ELLIPSIS_RESOLVED");
            log.info("[AI][chat] event=CONTEXT_REWRITE original={} rewritten={} focusedShopId={} candidateCount={}",
                    compact(query), compact(rewritten), context.getFocusedShopId(), context.getShownShops().size());
            return result;
        } catch (RuntimeException e) {
            log.warn("[AI][chat] event=CONTEXT_REWRITE_FALLBACK query={} errorType={}", compact(query), e.getClass().getSimpleName());
            return ContextRewriteResult.unchanged(query, "MODEL_FAILURE");
        }
    }

    private boolean needsRewrite(String query) {
        String[] signals = {"第一家", "第二家", "第三家", "这家", "那家", "上一家", "刚才那家", "走过去", "多远", "多久", "有包厢", "有优惠", "有券", "团购", "营业", "排队", "地址", "预约", "附近呢"};
        for (String signal : signals) if (query.contains(signal)) return true;
        return false;
    }

    private boolean refersToCurrentDeviceLocation(String query) {
        String normalized = query == null ? "" : query.replaceAll("\\s+", "");
        return normalized.matches("^(我?附近|当前(位置|定位)|边上)(呢|有啥|有什么)?[？?]?$")
                || normalized.contains("我附近") || normalized.contains("我这附近")
                || normalized.contains("当前位置") || normalized.contains("当前定位");
    }

    private String workingMemory(AgentSessionContext context) {
        List<String> candidates = new ArrayList<String>();
        for (int index = 0; index < context.getShownShops().size(); index++) {
            DecisionRecommendation item = context.getShownShops().get(index);
            candidates.add((index + 1) + ". " + item.getShopName() + "(id=" + item.getShopId() + ", 人均=" + item.getAvgPrice() + ")");
        }
        return "当前聚焦=" + context.getFocusedShopName() + "(id=" + context.getFocusedShopId() + ");活跃条件="
                + context.getDecisionConstraints() + ";候选=" + candidates;
    }

    private boolean hasBusinessContext(AgentSessionContext context) {
        if (context == null) return false;
        if (context.getShownShops() != null && !context.getShownShops().isEmpty()) return true;
        return context.getDecisionConstraints() != null
                && (hasText(context.getDecisionConstraints().getCuisine())
                || hasText(context.getDecisionConstraints().getTargetCity())
                || hasText(context.getDecisionConstraints().getTargetArea())
                || Boolean.TRUE.equals(context.getDecisionConstraints().getNearby()));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String compactHistory(List<Map<String, Object>> history) {
        StringBuilder text = new StringBuilder();
        int start = Math.max(0, history.size() - 6);
        for (int index = start; index < history.size(); index++) {
            Map<String, Object> item = history.get(index);
            if (text.length() > 0) text.append(" | ");
            text.append(item.get("role")).append(":").append(compact(String.valueOf(item.get("content"))));
        }
        return text.toString();
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String compact(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() > 180 ? normalized.substring(0, 180) + "..." : normalized;
    }
}
