package com.hmdp.ai.service;

import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.mapper.AiChatSessionMapper;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class ChatSessionStateService {
    @Resource private AiChatSessionMapper chatSessionMapper;

    public AiChatSession get(String chatId) {
        return chatSessionMapper.selectById(chatId);
    }

    public void activate(String chatId, Long decisionSessionId) {
        if (decisionSessionId == null) return;
        AiChatSession state = get(chatId);
        if (state == null) {
            state = new AiChatSession();
            state.setChatId(chatId);
            state.setUserId(UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
            state.setActiveDecisionSessionId(decisionSessionId);
            state.setLastDecisionSessionId(decisionSessionId);
            chatSessionMapper.insert(state);
            return;
        }
        state.setActiveDecisionSessionId(decisionSessionId);
        state.setLastDecisionSessionId(decisionSessionId);
        chatSessionMapper.updateById(state);
    }

    public void clearActive(String chatId) {
        AiChatSession state = get(chatId);
        if (state == null) return;
        state.setActiveDecisionSessionId(null);
        chatSessionMapper.updateById(state);
    }

    public void rememberLast(String chatId, Long decisionSessionId) {
        if (decisionSessionId == null) return;
        AiChatSession state = get(chatId);
        if (state == null) {
            state = new AiChatSession();
            state.setChatId(chatId);
            state.setUserId(UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
            state.setLastDecisionSessionId(decisionSessionId);
            chatSessionMapper.insert(state);
            return;
        }
        state.setLastDecisionSessionId(decisionSessionId);
        chatSessionMapper.updateById(state);
    }
}
