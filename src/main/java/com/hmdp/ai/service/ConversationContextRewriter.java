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
        if (query == null || query.trim().isEmpty()) return ContextRewriteResult.unchanged(query, "EMPTY_QUERY");
        if (context == null || context.getShownShops() == null || context.getShownShops().isEmpty()) {
            return ContextRewriteResult.unchanged(query, "NO_WORKING_MEMORY");
        }
        if (!needsRewrite(query)) return ContextRewriteResult.unchanged(query, "SELF_CONTAINED");
        if (!queryRewriteClient.isConfigured()) return ContextRewriteResult.unchanged(query, "MODEL_UNAVAILABLE");
        try {
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            messages.add(message("system", "你是餐饮 Agent 的上下文改写器。根据工作记忆把用户的省略句改写成一条自包含的中文业务查询。只输出改写后的查询，不回答问题、不编造商户或事实。若问第一家/第二家，必须使用候选列表对应名称；若说这家/那家，使用当前聚焦商户；若说换个便宜点的，明确为在当前候选范围寻找更低人均的备选。"));
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
        String[] signals = {"第一家", "第二家", "第三家", "这家", "那家", "上一家", "刚才那家", "换个", "便宜点", "贵点", "走过去", "多远", "多久", "有包厢", "有优惠", "有券", "团购"};
        for (String signal : signals) if (query.contains(signal)) return true;
        return false;
    }

    private String workingMemory(AgentSessionContext context) {
        List<String> candidates = new ArrayList<String>();
        for (int index = 0; index < context.getShownShops().size(); index++) {
            DecisionRecommendation item = context.getShownShops().get(index);
            candidates.add((index + 1) + ". " + item.getShopName() + "(id=" + item.getShopId() + ", 人均=" + item.getAvgPrice() + ")");
        }
        return "当前聚焦=" + context.getFocusedShopName() + "(id=" + context.getFocusedShopId() + ");候选=" + candidates;
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
