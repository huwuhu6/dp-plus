package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.DecisionResponse;
import org.springframework.stereotype.Service;

/** Mechanical projection from ResponseSpec; no model call and no state access is permitted here. */
@Service
public class V2ResponseRenderer {
    public ChatMessageResponse render(String chatId, ResponseSpec spec) {
        ChatMessageResponse response = new ChatMessageResponse();
        response.setChatId(chatId); response.setRoute("V2"); response.setUsedModel(false);
        DecisionResponse decision = new DecisionResponse();
        decision.setRecommendations(spec.recommendations());
        decision.setStatus(spec.staleSuppressed() ? "STALE_SUPPRESSED"
                : spec.unsupported() ? "UNSUPPORTED" : spec.clarification() != null ? "CLARIFICATION"
                : spec.partialError() == null ? "COMPLETED" : "PARTIAL");
        if (!spec.recommendations().isEmpty()) response.setAnswer("已为你找到 " + spec.recommendations().size() + " 家可选餐厅。");
        else if (spec.factAnswer() != null) response.setAnswer(spec.factAnswer());
        else if (spec.selectedShopId() != null) response.setAnswer(spec.selectedShopName() == null ? "已选定该商户。" : "已为你选定：" + spec.selectedShopName());
        else if (spec.generalAnswer() != null) response.setAnswer(spec.generalAnswer());
        else if (spec.clarification() != null) response.setAnswer(spec.clarification());
        else response.setAnswer(spec.partialError() == null ? "暂时没有满足条件的结果。" : spec.partialError());
        decision.setAnswer(response.getAnswer()); response.setDecision(decision);
        return response;
    }
}
