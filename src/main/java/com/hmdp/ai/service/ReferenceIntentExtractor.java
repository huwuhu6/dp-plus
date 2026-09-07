package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.dto.ReferenceIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts reference intent once; resolution is deliberately delegated to BatchAwareReferenceResolver. */
@Service
public class ReferenceIntentExtractor {
    private static final Logger log = LoggerFactory.getLogger(ReferenceIntentExtractor.class);
    private static final Pattern ORDINAL = Pattern.compile("(?:(最开始|最初|一开始)\s*)?(第一家|首选|第二家|第三家)");
    private static final Pattern FOCUSED = Pattern.compile("刚才那家|这家|那家|这个");

    @Autowired(required = false)
    private OpenAiCompatibleClient aiClient;
    @Autowired(required = false)
    private ObjectMapper objectMapper;

    public List<ReferenceIntent> extract(String message) {
        if (message == null || message.trim().isEmpty()) return new ArrayList<>();
        List<ReferenceIntent> rules = extractByRule(message);
        // Always ask the structured extractor about the uncovered spans.  A rule hit is
        // not evidence that the rest of the sentence contains no reference.
        return mergeRuleAndModel(message, rules, extractByModel(message));
    }

    private List<ReferenceIntent> mergeRuleAndModel(String message, List<ReferenceIntent> rules,
                                                     List<ReferenceIntent> modelIntents) {
        List<ReferenceIntent> merged = new ArrayList<>(rules);
        for (ReferenceIntent candidate : modelIntents) {
            ReferenceIntent overlap = overlapping(candidate, rules);
            if (overlap != null) {
                if (candidate.isMutationAnchor()) overlap.setMutationAnchor(true);
                continue;
            }
            if (overlapsAny(candidate, merged)) continue;
            merged.add(candidate);
        }
        merged.sort(Comparator.comparingInt(item -> item.getStart() == null ? Integer.MAX_VALUE : item.getStart()));
        return merged;
    }

    private boolean overlapsAny(ReferenceIntent candidate, List<ReferenceIntent> existing) {
        for (ReferenceIntent item : existing) {
            if (item.getStart() == null || item.getEnd() == null
                    || candidate.getStart() == null || candidate.getEnd() == null) continue;
            if (candidate.getStart() < item.getEnd() && item.getStart() < candidate.getEnd()) return true;
        }
        return false;
    }

    private ReferenceIntent overlapping(ReferenceIntent candidate, List<ReferenceIntent> existing) {
        for (ReferenceIntent item : existing) {
            if (item.getStart() == null || item.getEnd() == null
                    || candidate.getStart() == null || candidate.getEnd() == null) continue;
            if (candidate.getStart() < item.getEnd() && item.getStart() < candidate.getEnd()) return item;
        }
        return null;
    }

    private List<ReferenceIntent> extractByRule(String message) {
        List<ReferenceIntent> intents = new ArrayList<>();
        Matcher ordinal = ORDINAL.matcher(message);
        while (ordinal.find()) {
            String token = ordinal.group(2);
            int value = "第一家".equals(token) || "首选".equals(token) ? 1
                    : "第二家".equals(token) ? 2 : 3;
            ReferenceIntent.Scope scope = ordinal.group(1) == null
                    ? ReferenceIntent.Scope.LATEST : ReferenceIntent.Scope.EARLIEST;
            intents.add(new ReferenceIntent(scope, value, ordinal.group(), ordinal.start(), ordinal.end()));
        }
        Matcher focused = FOCUSED.matcher(message);
        while (focused.find()) {
            intents.add(new ReferenceIntent(ReferenceIntent.Scope.FOCUSED, null,
                    focused.group(), focused.start(), focused.end()));
        }
        intents.sort(Comparator.comparingInt(item -> item.getStart() == null ? Integer.MAX_VALUE : item.getStart()));
        return intents;
    }

    private List<ReferenceIntent> extractByModel(String message) {
        if (aiClient == null) return new ArrayList<>();
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(message("system", "从用户消息中提取商户指代。只输出结构化引用数组，不回答问题。scope 只能是 LATEST（当前最新候选批次）、EARLIEST（明确指最开始/历史候选批次）或 FOCUSED（刚才/当前聚焦商户）。ordinal 是从1开始的序数；FOCUSED 不填 ordinal。每个 intent 必须保留原文 surface 及其 start/end 字符位置，支持一句话多个引用。若该引用是用户明确评价/批评所针对的商户，将 mutationAnchor=true，否则为false。无法确定引用时返回空数组。"));
            messages.add(message("user", message));
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", "extract_reference_intents");
            function.put("description", "Extract structured shop references from one user message.");
            function.put("parameters", schema());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            JsonNode response = aiClient.chatCompletion(messages, List.of(tool), null, "REFERENCE_INTENT_EXTRACTION");
            String arguments = response.path("choices").path(0).path("message").path("tool_calls")
                    .path(0).path("function").path("arguments").asText();
            JsonNode intents = mapper().readTree(arguments).path("intents");
            List<ReferenceIntent> result = new ArrayList<>();
            if (!intents.isArray()) return result;
            for (JsonNode item : intents) {
                ReferenceIntent intent = new ReferenceIntent();
                try {
                    intent.setScope(ReferenceIntent.Scope.valueOf(item.path("scope").asText("LATEST")));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (item.hasNonNull("ordinal")) intent.setOrdinal(item.path("ordinal").asInt());
                intent.setSurface(item.path("surface").asText(null));
                intent.setStart(item.hasNonNull("start") ? item.path("start").asInt() : null);
                intent.setEnd(item.hasNonNull("end") ? item.path("end").asInt() : null);
                intent.setMutationAnchor(item.path("mutationAnchor").asBoolean(false));
                if (validateSpan(message, intent)
                        && (intent.getScope() == ReferenceIntent.Scope.FOCUSED
                        || (intent.getOrdinal() != null && intent.getOrdinal() > 0))) {
                    result.add(intent);
                }
            }
            result.sort(Comparator.comparingInt(item -> item.getStart() == null ? Integer.MAX_VALUE : item.getStart()));
            return result;
        } catch (Exception e) {
            log.debug("[AI][reference] event=EXTRACTION_FALLBACK errorType={}", e.getClass().getSimpleName());
            return new ArrayList<>();
        }
    }

    /**
     * Model positions are untrusted. Prefer the supplied span, otherwise accept only a
     * unique surface occurrence; never guess among multiple occurrences.
     */
    private boolean validateSpan(String message, ReferenceIntent intent) {
        String surface = intent.getSurface();
        if (surface == null || surface.isEmpty()) return false;
        Integer start = intent.getStart();
        Integer end = intent.getEnd();
        if (start != null && end != null && start >= 0 && start < end && end <= message.length()
                && message.substring(start, end).equals(surface)) return true;
        int first = message.indexOf(surface);
        if (first < 0 || message.indexOf(surface, first + surface.length()) >= 0) return false;
        intent.setStart(first);
        intent.setEnd(first + surface.length());
        return true;
    }

    private ObjectMapper mapper() {
        return objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    private Map<String, Object> schema() {
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("scope", Map.of("type", "string", "enum", List.of("LATEST", "EARLIEST", "FOCUSED")));
        props.put("ordinal", Map.of("type", "integer", "minimum", 1));
        props.put("surface", Map.of("type", "string"));
        props.put("start", Map.of("type", "integer", "minimum", 0));
        props.put("end", Map.of("type", "integer", "minimum", 0));
        props.put("mutationAnchor", Map.of("type", "boolean"));
        intent.put("properties", props);
        intent.put("required", List.of("scope", "surface", "start", "end"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("intents", Map.of("type", "array", "items", intent)));
        schema.put("required", List.of("intents"));
        return schema;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("role", role);
        value.put("content", content);
        return value;
    }
}
