package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.v2.semantic.UserRequest;

/** 请求、对象、属性和证据状态共同记录；工具调用 SUCCESS 不代表事实已知。 */
public record MerchantFactObservation(long shopId,UserRequest.FactType fact,String question,State state,String source) {
    public enum State { KNOWN, UNKNOWN }
}
