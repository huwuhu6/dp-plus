package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.mapper.AiWorkingMemoryMapper;
import com.hmdp.ai.runtime.ConversationEventType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Isolated race probe: legacy last-write-wins slot model versus the production
 * WorkingMemoryVersionService.append CAS path called in PRE and POST.
 * This is an in-memory persistence stub, NOT an end-to-end MySQL/chat benchmark.
 */
class WorkingMemoryConcurrencyProbeTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String CHAT = "same-chat";
    private record Result(int n, int oldLost, int newLost, int conflicts, int preCommits, int postCommits) { }

    @Test void n2() throws Exception { runAndAssert(2); }
    @Test void n4() throws Exception { runAndAssert(4); }
    @Test void n8() throws Exception { runAndAssert(8); }

    private void runAndAssert(int n) throws Exception {
        // Every legacy writer reads the same old snapshot; the last overwrite wins.
        AtomicReference<Set<Integer>> legacySlots = new AtomicReference<>(Set.of());
        ConcurrentSkipListMap<Integer,AiWorkingMemory> versions = new ConcurrentSkipListMap<>();
        AiWorkingMemoryMapper mapper = mock(AiWorkingMemoryMapper.class);
        AiConversationEventMapper eventMapper = mock(AiConversationEventMapper.class);
        ConversationEventService eventService = mock(ConversationEventService.class);
        when(mapper.selectOne(any(QueryWrapper.class))).thenAnswer(invocation ->
                versions.isEmpty() ? null : versions.lastEntry().getValue());
        when(mapper.insert(any(AiWorkingMemory.class))).thenAnswer(invocation -> {
            AiWorkingMemory row = invocation.getArgument(0);
            AiWorkingMemory stored = new AiWorkingMemory();
            stored.setChatId(row.getChatId()); stored.setVersion(row.getVersion());
            stored.setMemoryJson(row.getMemoryJson()); stored.setId((long)row.getVersion());
            if (versions.putIfAbsent(row.getVersion(),stored) != null)
                throw new DuplicateKeyException("same-chat version duplicate");
            row.setId((long)row.getVersion());
            return 1;
        });
        when(eventService.newStateEvent(any(), any(), any(), any(), any())).thenReturn(new AiConversationEvent());
        when(eventMapper.insert(any(AiConversationEvent.class))).thenReturn(1);
        WorkingMemoryVersionService actual = new WorkingMemoryVersionService();
        ReflectionTestUtils.setField(actual,"workingMemoryMapper",mapper);
        ReflectionTestUtils.setField(actual,"eventMapper",eventMapper);
        ReflectionTestUtils.setField(actual,"eventService",eventService);
        ReflectionTestUtils.setField(actual,"objectMapper",json);

        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger pres = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for(int i=1;i<=n;i++) {
                final int marker=i;
                futures.add(pool.submit(() -> {
                    Set<Integer> stale = legacySlots.get();
                    ready.countDown();
                    if(!go.await(15,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");
                    HashSet<Integer> overwrite = new HashSet<>(stale); overwrite.add(marker);
                    legacySlots.set(Set.copyOf(overwrite));

                    // PRE: refresh on every CAS conflict, preserving each distinct update.
                    appendMarker(actual, marker, "V2_PRE", conflicts);
                    pres.incrementAndGet();
                    // POST: a second OCC append using latest state; retries on conflict.
                    appendMarker(actual, marker, "V2_POST", conflicts);
                    posts.incrementAndGet();
                    return null;
                }));
            }
            assertTrue(ready.await(15,TimeUnit.SECONDS),"workers not ready");
            go.countDown();
            for(Future<?> f:futures)f.get(30,TimeUnit.SECONDS);
        }finally {
            go.countDown(); pool.shutdownNow();
        }
        int oldLost=n-legacySlots.get().size();
        AiWorkingMemory latest=actual.latest(CHAT);
        Set<Integer> persisted = json.readValue(latest.getMemoryJson(),new TypeReference<Set<Integer>>(){});
        int newLost=n-persisted.size();
        System.out.printf("OCC_PROBE N=%d OLD_LOST=%d OCC_LOST=%d OCC_CONFLICTS=%d PRE=%d POST=%d FINAL_VERSION=%d%n",
                n,oldLost,newLost,conflicts.get(),pres.get(),posts.get(),latest.getVersion());
        assertEquals(n-1,oldLost,"forced stale slot baseline must be last-write-wins");
        assertEquals(0,newLost,"versioned CAS with caller rebase may not silently overwrite markers");
        assertEquals(n,pres.get()); assertEquals(n,posts.get());
        assertEquals(n*2,latest.getVersion(),"each accepted turn has a PRE and a POST");
    }

    private void appendMarker(WorkingMemoryVersionService actual,int marker,String phase,AtomicInteger conflicts) throws Exception {
        for(int attempt=0;attempt<100;attempt++) {
            AiWorkingMemory last = actual.latest(CHAT);
            int version=last==null?0:last.getVersion();
            Set<Integer> value=last==null?new HashSet<>() :
                    json.readValue(last.getMemoryJson(), new TypeReference<HashSet<Integer>>(){});
            Set<Integer> merged = new HashSet<>(value); merged.add(marker);
            try {
                actual.append(CHAT,null,version,merged,ConversationEventType.STATE_REDUCED,
                        phase,Map.of("phase",phase),phase);
                return;
            }catch(VersionConflictException e) {
                conflicts.incrementAndGet();
                Thread.yield();
            }
        }
        throw new AssertionError("CAS retry budget exceeded");
    }
}
