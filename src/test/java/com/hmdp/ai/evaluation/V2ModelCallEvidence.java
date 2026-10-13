package com.hmdp.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.web.client.HttpStatusCodeException;

/** test scope 旁路：复制实际模型参数/返回，不接触HTTP认证头，不改变调用或触发重试。 */
final class V2ModelCallEvidence {
    final ObjectMapper json;
    final List<Map<String,Object>> calls=new ArrayList<>();
    V2ModelCallEvidence(ObjectMapper json) { this.json=json; }

    JsonNode observe(List<Map<String,Object>> messages,List<Map<String,Object>> tools,Map<String,Object> choice,
                     String action,Supplier<JsonNode> actualCall) {
        Map<String,Object> call=new LinkedHashMap<>();
        call.put("messages",json.valueToTree(messages));call.put("tools",json.valueToTree(tools));
        call.put("toolChoice",json.valueToTree(choice));call.put("action",action);
        try {call.put("inputSha256",V2RealModelPilotTest.sha256(json.writeValueAsBytes(call)));}
        catch(Exception failure){throw new IllegalStateException("cannot fingerprint model input",failure);}
        call.put("startedAt",java.time.Instant.now().toString());
        long started=System.nanoTime();
        try {
            JsonNode response=actualCall.get();
            call.put("response",response.deepCopy());call.put("usage",response.path("usage").deepCopy());call.put("success",true);
            return response;
        } catch(RuntimeException failure) {
            call.put("success",false);call.put("failureType",failure.getClass().getName());
            if(failure instanceof HttpStatusCodeException http)call.put("httpStatus",http.getStatusCode().value());
            // 不保存可能含请求URL/认证信息的异常message；完整业务与模型原始输出另有可信记录。
            throw failure;
        } finally {
            call.put("durationMs",(System.nanoTime()-started)/1_000_000);calls.add(call);
        }
    }
}
