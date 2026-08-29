package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.runtime.ConversationEventStatus;
import com.hmdp.ai.runtime.ConversationEventType;
import com.hmdp.ai.runtime.ConversationEventReliability;
import com.hmdp.ai.runtime.RuntimeTrace;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /** Records only observability events. Durable facts must use persistDurableEvent. */
    public Long recordBestEffort(ConversationEventType type, ConversationEventStatus status,
                                 Long workingMemoryId, Long parentEventId, Object result, Map<String, Object> metadata) {
        requireReliability(type, ConversationEventReliability.BEST_EFFORT);
        RuntimeTrace trace = traceHolder.get();
        if (trace == null) return null;
        AiConversationEvent event = build(trace, type, status, workingMemoryId, parentEventId, result, metadata);
        buffer.add(event);
        scheduleFlush();
        return event.getId();
    }

    /**
     * Persists one interaction fact on a short independent transaction. It deliberately
     * never shares the in-memory observability buffer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long persistDurableEvent(ConversationEventType type, ConversationEventStatus status,
                                    Long workingMemoryId, Long parentEventId, Object result,
                                    Map<String, Object> metadata) {
        RuntimeTrace trace = traceHolder.get();
        if (trace == null) throw new IllegalStateException("Durable conversation event requires an active runtime trace");
        return persistDurableEvent(trace, type, status, workingMemoryId, parentEventId, result, metadata);
    }

    /** Allows parallel tool workers to reuse the request trace without ThreadLocal propagation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long persistDurableEvent(RuntimeTrace trace, ConversationEventType type, ConversationEventStatus status,
                                    Long workingMemoryId, Long parentEventId, Object result,
                                    Map<String, Object> metadata) {
        requireReliability(type, ConversationEventReliability.DURABLE);
        if (trace == null) throw new IllegalStateException("Durable conversation event requires a runtime trace");
        AiConversationEvent event = build(trace, type, status, workingMemoryId, parentEventId, result, metadata);
        try {
            int inserted = eventMapper.insert(event);
            if (inserted != 1) throw new IllegalStateException("Durable conversation event was not persisted");
            return event.getId();
        } catch (DuplicateKeyException duplicate) {
            AiConversationEvent existing = eventMapper.selectById(event.getId());
            if (existing == null) {
                existing = eventMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AiConversationEvent>()
                        .eq("trace_id", event.getTraceId()).eq("sequence_no", event.getSequenceNo()).last("limit 1"));
            }
            if (existing != null && sameEvent(existing, event)) return existing.getId();
            throw new IllegalStateException("Conversation event identity conflicts with a different payload", duplicate);
        }
    }

    public AiConversationEvent newStateEvent(RuntimeTrace trace, ConversationEventType type,
                                             Long workingMemoryId, Object result, Map<String, Object> metadata) {
        requireReliability(type, ConversationEventReliability.DURABLE);
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

    public boolean isTraceIncomplete() {
        RuntimeTrace trace = traceHolder.get();
        return trace != null && trace.isIncomplete();
    }

    public void markTraceIncomplete(String reason) {
        RuntimeTrace trace = traceHolder.get();
        if (trace != null) trace.markIncomplete();
        log.warn("[AI][runtime] event=TRACE_INCOMPLETE reason={}", reason);
    }

    private void requireReliability(ConversationEventType type, ConversationEventReliability expected) {
        if (reliabilityOf(type) != expected) {
            throw new IllegalArgumentException("Event " + type + " must use " + reliabilityOf(type) + " persistence");
        }
    }

    public ConversationEventReliability reliabilityOf(ConversationEventType type) {
        if (type == ConversationEventType.USER_INPUT || type == ConversationEventType.ASSISTANT_OUTPUT
                || type == ConversationEventType.TOOL_CALL || type == ConversationEventType.TOOL_RESULT
                || type == ConversationEventType.STATE_REDUCED) return ConversationEventReliability.DURABLE;
        return ConversationEventReliability.BEST_EFFORT;
    }

    private boolean sameEvent(AiConversationEvent left, AiConversationEvent right) {
        return java.util.Objects.equals(left.getId(), right.getId())
                && java.util.Objects.equals(left.getChatId(), right.getChatId())
                && java.util.Objects.equals(left.getTraceId(), right.getTraceId())
                && java.util.Objects.equals(left.getTurnNo(), right.getTurnNo())
                && java.util.Objects.equals(left.getSequenceNo(), right.getSequenceNo())
                && java.util.Objects.equals(left.getEventType(), right.getEventType())
                && java.util.Objects.equals(left.getStatus(), right.getStatus())
                && java.util.Objects.equals(left.getWorkingMemoryId(), right.getWorkingMemoryId())
                && java.util.Objects.equals(left.getParentEventId(), right.getParentEventId())
                && java.util.Objects.equals(left.getEventResult(), right.getEventResult())
                && java.util.Objects.equals(left.getMetadata(), right.getMetadata());
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
