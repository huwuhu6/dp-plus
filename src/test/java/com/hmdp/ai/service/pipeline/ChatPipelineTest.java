package com.hmdp.ai.service.pipeline;

import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ChatStreamEventData;
import com.hmdp.ai.dto.ContextRewriteResult;
import com.hmdp.ai.dto.DecisionConstraints;
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

    @Test
    void publishesRunningAndSuccessStatusAroundEachNode() {
        List<ChatStreamEventData> events = new ArrayList<ChatStreamEventData>();
        ChatPipeline pipeline = new ChatPipeline(Arrays.asList(new ChatPipelineNode() {
            @Override public void process(ChatProcessingContext context) { }
            @Override public String statusMessage() { return "正在测试节点状态"; }
        }));
        ChatProcessingContext context = new ChatProcessingContext(new ChatMessageRequest(), null);
        context.setEventConsumer(events::add);

        pipeline.process(context);

        assertEquals(2, events.size());
        assertEquals("node_status", events.get(0).getEventName());
        assertEquals("running", events.get(0).getStatus());
        assertEquals("success", events.get(1).getStatus());
        assertEquals("正在测试节点状态", events.get(0).getMessage());
    }

    @Test
    void publishesNodeSpecificSuccessMetadata() {
        List<ChatStreamEventData> events = new ArrayList<ChatStreamEventData>();
        ChatPipelineOperations operations = new ChatPipelineOperations() {
            @Override public void bootstrap(ChatProcessingContext context) { }
            @Override public void rewrite(ChatProcessingContext context) {
                ContextRewriteResult rewrite = new ContextRewriteResult();
                rewrite.setRewrittenQuery("查询附近川菜");
                context.setContextRewrite(rewrite);
            }
            @Override public void route(ChatProcessingContext context) {
                context.setAction(ChatProcessingAction.START_DECISION);
                context.setRoutingReason("new_recommendation_intent");
            }
            @Override public void reduceCriteria(ChatProcessingContext context) {
                DecisionConstraints criteria = new DecisionConstraints();
                criteria.setCuisine("川菜");
                context.setMergedConstraints(criteria);
            }
            @Override public void applyPolicyGuard(ChatProcessingContext context) { }
            @Override public void execute(ChatProcessingContext context) { }
        };
        ChatPipeline pipeline = new ChatPipeline(Arrays.asList(
                new ContextRewriteNode(operations), new IntentRoutingNode(operations),
                new CriteriaReductionNode(operations), new ExecutionNode(operations)));
        ChatProcessingContext context = new ChatProcessingContext(new ChatMessageRequest(), null);
        context.setEventConsumer(events::add);

        pipeline.process(context);

        assertEquals("查询附近川菜", events.get(1).getMetadata().get("rewrittenQuery"));
        assertEquals(ChatProcessingAction.START_DECISION, events.get(3).getMetadata().get("action"));
        assertEquals("new_recommendation_intent", events.get(3).getMetadata().get("reason"));
        assertEquals("川菜", ((DecisionConstraints) events.get(5).getMetadata().get("criteria")).getCuisine());
        assertEquals(ChatProcessingAction.START_DECISION, events.get(7).getMetadata().get("executionMode"));
    }
}
