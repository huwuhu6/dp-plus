package com.hmdp.ai.v2.grounding;

import com.hmdp.ai.v2.semantic.*;
import java.util.*;
import java.util.regex.*;

/** 仅消除“具体商户/一般价格”对象歧义，不产出 shopId。后续 Grounding 仍检查名称、批次及序号冲突。 */
public final class MerchantPriceContextResolver {
    public TurnSemantics resolve(String user,TurnSemantics semantics,List<TaskView> tasks){
        if(!user.matches("(?s).*(?:多少钱|价格|价钱|费用|人均).*" )
                || user.matches("(?s).*(?:一般|通常|普遍|历史|起源|为什么|全国).*"))return semantics;
        var visible=tasks.stream().flatMap(t->t.recommendationBatches().stream()).flatMap(b->b.candidates().stream()).toList();
        List<UserRequest> requests=new ArrayList<>();
        for(UserRequest request:semantics.requests()){
            EntityReference ref=null;
            if(request instanceof UserRequest.GeneralKnowledgeRequest general && general.topic().matches("(?s).*(?:多少钱|价格|价钱|费用|人均|消费).*")){
                // 模型主题只用作字面候选；必须同时有原话来源及已展示商户描述，不按“多少钱”直接升级。
                String subject=general.topic().replaceAll("(?:大概|价格|价钱|费用|多少钱|人均|消费|[？?\\s])","");
                if(!subject.isBlank() && user.contains(subject) && visible.stream().anyMatch(s->s.matchesDescription(subject)))
                    ref=new EntityReference.NamedEntityRef(subject);
                else {
                    Matcher ordinal=Pattern.compile("第([一二三四五六七八九十]|[0-9]+)家").matcher(user);
                    if(ordinal.find() && !visible.isEmpty()){
                        String value=ordinal.group(1);int n=value.matches("[0-9]+")?Integer.parseInt(value):"一二三四五六七八九十".indexOf(value)+1;
                        if(!ordinal.find())ref=new EntityReference.OrdinalRef(n);
                    }
                }
            }
            requests.add(ref==null?request:new UserRequest.FactQueryRequest(request.requestId(),ref,UserRequest.FactType.PRICE));
        }
        return new TurnSemantics(semantics.taskDirective(),semantics.taskDirectiveEvidence(),semantics.requirementChanges(),
                semantics.entityFeedback(),requests,semantics.relations(),semantics.references());
    }
}
