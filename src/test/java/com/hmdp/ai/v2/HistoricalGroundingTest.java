package com.hmdp.ai.v2;

import com.hmdp.ai.v2.grounding.*;
import com.hmdp.ai.v2.semantic.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalGroundingTest {
    final GroundingResolver resolver=new GroundingResolver();
    TaskView.RecommendationBatchView batch(String id,long a,String an,long b,String bn) {
        return new TaskView.RecommendationBatchView(id,List.of(new TaskView.ShopView(a,an),new TaskView.ShopView(b,bn)));
    }
    EffectiveTaskContext context(TaskView.RecommendationBatchView... batches) {
        return new EffectiveTaskContext(new TaskView("task",0,"DINING",null,batches[batches.length-1],null,List.of(batches)),false);
    }
    EffectiveTaskContext normal() {return context(batch("B1",11,"西餐厅",22,"澳洲牛排馆"),batch("B2",33,"简餐",66,"海鲜馆"),batch("B3",44,"社区西餐吧",55,"精酿牛排小馆"));}
    GroundingResult resolve(EntityReference ref,String text,EffectiveTaskContext context) {
        return resolver.resolve(new TurnSemantics(TaskDirective.CONTINUE,List.of(),List.of(),List.of(new UserRequest.FactQueryRequest("r",ref,UserRequest.FactType.DETAIL)),List.of()),context,text);
    }
    void identity(EntityReference ref,String text,EffectiveTaskContext context,long id,String batch) {
        var result=assertInstanceOf(GroundingResult.Grounded.class,resolve(ref,text,context));
        var shop=assertInstanceOf(GroundedReference.ShopIdentity.class,result.turn().requests().getFirst().operands().getFirst());
        assertEquals(id,shop.shopId());assertEquals(batch,shop.batchId());
    }
    @Test void currentFirst() {identity(new EntityReference.OrdinalRef(1),"第一家地址",normal(),44,"B3");}
    @Test void currentSecond() {identity(new EntityReference.OrdinalRef(2),"第二家地址",normal(),55,"B3");}
    @Test void namedCurrent() {identity(new EntityReference.NamedEntityRef("社区西餐吧"),"社区西餐吧地址",normal(),44,"B3");}
    @Test void previousBatchNamed() {identity(new EntityReference.NamedEntityRef("海鲜馆"),"上一批海鲜馆地址",normal(),66,"B2");}
    @Test void firstBatchNamed() {identity(new EntityReference.NamedEntityRef("澳洲牛排馆"),"最初推荐的澳洲牛排馆地址",normal(),22,"B1");}
    @Test void wrongOrdinalCannotOverrideHistoricalName() {identity(new EntityReference.OrdinalRef(1),"最开始推荐的澳洲牛排馆地址",normal(),22,"B1");}
    @Test void previousOrdinal() {identity(new EntityReference.OrdinalRef(2),"上一批第二家地址",normal(),66,"B2");}
    @Test void earliestOrdinal() {identity(new EntityReference.OrdinalRef(2),"第一批第二家地址",normal(),22,"B1");}
    @Test void duplicateNamesDifferentIdentitiesClarify() {
        var ctx=context(batch("B1",11,"连锁店",22,"其他"),batch("B2",44,"连锁店",55,"不同"));
        assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.NamedEntityRef("连锁店"),"连锁店地址",ctx));
        identity(new EntityReference.NamedEntityRef("连锁店"),"最初推荐的连锁店地址",ctx,11,"B1");
        identity(new EntityReference.NamedOrdinalRef("连锁店",1),"当前第一家连锁店地址",ctx,44,"B2");
    }
    @Test void sameIdentityRepeatedIsNotAmbiguous() {
        identity(new EntityReference.NamedEntityRef("连锁店"),"连锁店地址",context(batch("B1",11,"连锁店",22,"其他"),batch("B2",11,"连锁店",55,"不同")),11,"B2");
    }
    @Test void nameAndOrdinalConflictClarify() {assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.OrdinalRef(1),"第二家精酿牛排小馆地址",normal()));}
    @Test void unknownHistoricalNameCannotFallbackToCurrentFirst() {assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.OrdinalRef(1),"最开始推荐的已不存在商户地址",normal()));}
    @Test void ambiguousHistoricalWithoutScopeCannotGuess() {assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.OrdinalRef(1),"之前推荐的那家店地址",normal()));}
    @Test void inventedCurrentOrdinalCannotExecuteForAmbiguousUserReference() {assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.OrdinalRef(1),"那家店地址",normal()));}
    @Test void historicalRoundOrdinalWithoutBatchEvidenceCannotBecomeCurrentOrdinal() {assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.OrdinalRef(1),"上次第一家地址",normal()));}
    @Test void nonexistentNamedCannotExecute() {assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.NamedEntityRef("不存在"),"不存在地址",normal()));}
    @Test void modelNameConflictsWithUserName() {assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.NamedEntityRef("社区西餐吧"),"澳洲牛排馆地址",normal()));}
    @Test void namedReferenceMustRespectUserOrdinalToo() {assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.NamedEntityRef("精酿牛排小馆"),"第一家精酿牛排小馆地址",normal()));}
    @Test void nameOutsideHistoricalScopeCannotFallbackToHistoricalOrdinal() {assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.OrdinalRef(1),"最初第一家社区西餐吧地址",normal()));}
    @Test void historicalNameConflictsWithCurrentOrdinalEvenForNamedModelOutput() {assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.NamedEntityRef("澳洲牛排馆"),"第一家澳洲牛排馆地址",normal()));}
    @Test void jointSemanticNameOrdinalPositive() {identity(new EntityReference.NamedOrdinalRef("精酿牛排小馆",2),"第二家精酿牛排小馆地址",normal(),55,"B3");}
    @Test void jointSemanticNameOrdinalConflictWithoutRawName() {assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(new EntityReference.NamedOrdinalRef("精酿牛排小馆",1),"查详情",normal()));}
    @Test void interpreterDoesNotDiscardJointName() throws Exception {
        var interpreter=new com.hmdp.ai.v2.runtime.SemanticInterpreter();
        var semantics=interpreter.parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
            {"requests":[{"requestId":"r1","type":"FACT","target":{"ordinal":1,"name":"精酿牛排小馆"},"fact":"DETAIL"}]}
            """));
        var ref=assertInstanceOf(UserRequest.FactQueryRequest.class,semantics.requests().getFirst()).target();
        assertInstanceOf(EntityReference.NamedOrdinalRef.class,ref);
        assertInstanceOf(GroundingResult.NeedsClarification.class,resolve(ref,"查详情",normal()));
    }
}
