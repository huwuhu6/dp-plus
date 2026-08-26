package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.runtime.ConversationEventStatus;
import com.hmdp.ai.runtime.ConversationEventType;
import com.hmdp.ai.runtime.RuntimeTrace;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects non-state runtime events off the request path. State-changing events are
 * persisted directly by WorkingMemoryVersionService in the same transaction as the
 * new memory version.
 */
@Service
public class ConversationEventService {
    private static final Logger log = LoggerFactory.getLogger(ConversationEventService.class);
    private static final int MAX_BATCH_SIZE = 100;
    private final ThreadLocal<RuntimeTrace> traceHolder = new ThreadLocal<RuntimeTrace>();
    private final ConcurrentLinkedQueue<AiConversationEvent> buffer = new ConcurrentLinkedQueue<AiConversationEvent>();
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ai-event-buffer-", 0).factory());

    @Resource private AiConversationEventMapper eventMapper;
    @Resource private ObjectMapper objectMapper;

    public RuntimeTrace begin(String chatId, int turnNo) {
        RuntimeTrace trace = new RuntimeTrace(chatId, UUID.randomUUID().toString(), turnNo);
        traceHolder.set(trace);
        return trace;
    }

    public RuntimeTrace currentTrace() { return traceHolder.get(); }
    public void clearTrace() { traceHolder.remove(); }

    public Long record(ConversationEventType type, ConversationEventStatus status,
                       Long workingMemoryId, Long parentEventId, Object result, Map<String, Object> metadata) {
        RuntimeTrace trace = traceHolder.get();
        if (trace == null) return null;
        AiConversationEvent event = build(trace, type, status, workingMemoryId, parentEventId, result, metadata);
        buffer.add(event);
        scheduleFlush();
        return event.getId();
    }

    public AiConversationEvent newStateEvent(RuntimeTrace trace, ConversationEventType type,
                                             Long workingMemoryId, Object result, Map<String, Object> metadata) {
        RuntimeTrace effective = trace == null
                ? new RuntimeTrace("system", UUID.randomUUID().toString(), 0) : trace;
        return build(effective, type, ConversationEventStatus.SUCCESS, workingMemoryId, null, result, metadata);
    }

    private AiConversationEvent build(RuntimeTrace trace, ConversationEventType type, ConversationEventStatus status,
                                      Long workingMemoryId, Long parentEventId, Object result, Map<String, Object> metadata) {
        LocalDateTime now = LocalDateTime.now();
        AiConversationEvent event = new AiConversationEvent();
        event.setId(IdWorker.getId());
        event.setChatId(trace.getChatId()); event.setTraceId(trace.getTraceId());
        event.setTurnNo(trace.getTurnNo()); event.setSequenceNo(trace.nextSequence());
        event.setEventType(type.name()); event.setStatus(status.name());
        event.setWorkingMemoryId(workingMemoryId); event.setParentEventId(parentEventId);
        event.setEventResult(write(result)); event.setMetadata(write(metadata));
        event.setStartedAt(now); event.setEndedAt(now); event.setCreatedAt(now);
        return event;
    }

    private String write(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "{\"serializationError\":true}"; }
    }

    private void scheduleFlush() {
        if (!flushing.compareAndSet(false, true)) return;
        eventExecutor.execute(this::flush);
    }

    void flush() {
        try {
            List<AiConversationEvent> batch = new ArrayList<AiConversationEvent>(MAX_BATCH_SIZE);
            AiConversationEvent event;
            while ((event = buffer.poll()) != null && batch.size() < MAX_BATCH_SIZE) batch.add(event);
            if (!batch.isEmpty()) eventMapper.insertBatch(batch);
        } catch (Exception e) {
            log.warn("[AI][runtime] event=EVENT_BATCH_PERSIST_FAILURE errorType={}", e.getClass().getSimpleName());
        } finally {
            flushing.set(false);
            if (!buffer.isEmpty()) scheduleFlush();
        }
    }

    /** Used by deterministic evaluation before asserting runtime traces. */
    public void flushNow() { flush(); }

    @PreDestroy
    public void shutdown() {
        flush();
        eventExecutor.shutdown();
    }
}
