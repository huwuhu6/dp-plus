package com.hmdp.ai.v2.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.BiPredicate;

/** 同一轮的字段级纠错，不是 Working Memory 合并，也不把未经验证的 FIRST 当事实。 */
final class SemanticRepair {
    private final JsonNode first;
    private final Map<String,JsonNode> protectedFields = new LinkedHashMap<>();
    private final List<String> failedDimensions;
    private final BiPredicate<String,JsonNode> sourceCheck;
    private final BiPredicate<String,List<JsonNode>> equivalent;
    private final Map<String,String> decisions = new LinkedHashMap<>();
    private final Map<String,Object> evidence = new LinkedHashMap<>();
    private boolean mergeCompleted;

    SemanticRepair(JsonNode first, List<String> failedDimensions, BiPredicate<String,JsonNode> sourceCheck,
                   BiPredicate<String,List<JsonNode>> equivalent) {
        this.first=first;this.failedDimensions=failedDimensions;this.sourceCheck=sourceCheck;this.equivalent=equivalent;
        fields(first).forEach((path,value)->{
            if (present(value) && !failedDimensions.contains(path) && sourceCheck.test(path,value)) {
                protectedFields.put(path,value.deepCopy());decisions.put(path,"SOURCE_CHECKED_FIRST");
            } else if (present(value)) decisions.put(path,"UNPROTECTED_FIRST: failed dimension or insufficient source/schema evidence");
        });
    }
    Map<String,Object> context() {
        Map<String,Object> context=new LinkedHashMap<>();
        context.put("firstStructuredOutput",first);context.put("failedDimensions",failedDimensions);
        context.put("sourceCheckedFieldsToPreserve",protectedFields);context.put("fieldAssessment",decisions);
        return context;
    }
    JsonNode merge(JsonNode candidate) {
        if (candidate==null || !candidate.isObject()) throw new IllegalArgumentException("repair must return an object");
        ObjectNode result=candidate.deepCopy();
        Map<String,JsonNode> supplied=fields(candidate);
        supplied.forEach((path,value)->{
            if (present(value) && !sourceCheck.test(path,value)) {
                decisions.put(path,"REJECTED_REPAIR: schema/user-source check failed");
                throw new IllegalArgumentException("repair field failed schema/user-source check: "+path);
            }
        });
        protectedFields.forEach((path,value)->{
            JsonNode replacement=supplied.get(path);
            if (present(replacement) && !equivalent.test(path,List.of(value,replacement)))
                throw new IllegalArgumentException("repair conflicts with source-checked FIRST field: "+path);
            put(result,path,value);
            decisions.put(path,"PRESERVED_FIRST: source checked; omitted/empty or semantically equivalent repair");
        });
        fields(result).forEach((path,value)->{
            if (present(value) && !protectedFields.containsKey(path)) decisions.put(path,"REPAIRED: schema and user source checked; full contract validation still required");
        });
        fields(first).forEach((path,value)->{
            if (present(value) && !present(fields(result).get(path))) decisions.put(path,"DISCARDED_FIRST: unprotected invalid/incomplete field");
        });
        Set<String> paths=new LinkedHashSet<>(fields(first).keySet());paths.addAll(supplied.keySet());
        Map<String,JsonNode> completed=fields(result);
        for(String path:paths) {
            Map<String,Object> field=new LinkedHashMap<>();
            field.put("firstValue",fields(first).get(path));field.put("repairValue",supplied.get(path));field.put("finalValue",completed.get(path));
            field.put("origin",protectedFields.containsKey(path)?"SOURCE_CHECKED_FIRST":"REPAIR_OR_DISCARD");
            field.put("reason",decisions.getOrDefault(path,"empty/untouched"));
            field.put("finalUserSourceChecked",!present(completed.get(path)) || sourceCheck.test(path,completed.get(path)));
            evidence.put(path,field);
        }
        mergeCompleted=true;return result;
    }
    Map<String,String> decisions() { return Map.copyOf(decisions); }
    Map<String,Object> evidence() { return Map.copyOf(evidence); }
    boolean mergeCompleted() { return mergeCompleted; }
    private static boolean present(JsonNode value) { return value!=null && !value.isNull() && !(value.isContainerNode() && value.isEmpty()) && !(value.isTextual() && value.asText().isBlank()); }
    private static Map<String,JsonNode> fields(JsonNode node) {
        Map<String,JsonNode> result=new LinkedHashMap<>();
        if (node!=null && node.isObject()) node.fields().forEachRemaining(entry->{
            if (entry.getKey().equals("criteria") && entry.getValue().isObject()) entry.getValue().fields()
                    .forEachRemaining(field->result.put("criteria."+field.getKey(),field.getValue()));
            else result.put(entry.getKey(),entry.getValue());
        });
        return result;
    }
    private static void put(ObjectNode root,String path,JsonNode value) {
        if (path.startsWith("criteria.")) {
            JsonNode node=root.get("criteria");
            ObjectNode criteria=node!=null && node.isObject()?(ObjectNode)node:root.putObject("criteria");
            criteria.set(path.substring(9),value.deepCopy());
        } else root.set(path,value.deepCopy());
    }
}
