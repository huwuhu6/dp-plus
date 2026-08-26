package com.hmdp.ai.runtime;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;

/** Request-scoped observability context; it never stores business state. */
@Getter
public class RuntimeTrace {
    private final String chatId;
    private final String traceId;
    private final int turnNo;
    private final AtomicInteger sequence = new AtomicInteger();

    public RuntimeTrace(String chatId, String traceId, int turnNo) {
        this.chatId = chatId;
        this.traceId = traceId;
        this.turnNo = turnNo;
    }

    public int nextSequence() { return sequence.incrementAndGet(); }
}
