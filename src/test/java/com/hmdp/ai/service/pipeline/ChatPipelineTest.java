package com.hmdp.ai.service.pipeline;

import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatPipelineTest {
    @Test
    void processesNodesInDeclaredOrder() {
        List<String> events = new ArrayList<String>();
        ChatPipeline pipeline = new ChatPipeline(Arrays.asList(
                context -> events.add("rewrite"),
                context -> events.add("route"),
                context -> events.add("execute")));

        pipeline.process(new ChatProcessingContext(new ChatMessageRequest(), null));

        assertEquals(Arrays.asList("rewrite", "route", "execute"), events);
    }

    @Test
    void stopsAfterClarificationNodeProducesResponse() {
        List<String> events = new ArrayList<String>();
        ChatPipeline pipeline = new ChatPipeline(Arrays.asList(
                context -> {
                    events.add("guard");
                    context.setResponse(new ChatMessageResponse());
                },
                context -> events.add("must-not-run")));

        pipeline.process(new ChatProcessingContext(new ChatMessageRequest(), null));

        assertEquals(Arrays.asList("guard"), events);
    }
}
