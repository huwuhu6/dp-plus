package com.hmdp.ai.v2.runtime;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.dto.SemanticRecallResult;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.ai.service.SemanticShopRetriever;
import com.hmdp.ai.v2.plan.ExecutionAction;
import com.hmdp.ai.v2.plan.SearchAnchor;
import com.hmdp.ai.v2.plan.SearchSpec;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.RequirementChange;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShopRetrievalEngineTest {
    @Test void foodTypeSynonymMatchesButBroadCategoryDoesNotProveSpecificDish() {
        ShopMapper shops=mock(ShopMapper.class);AiShopProfileMapper profiles=mock(AiShopProfileMapper.class);
        AiReviewDocumentMapper reviews=mock(AiReviewDocumentMapper.class);
        when(shops.selectList(any())).thenReturn(List.of(shop(1L,"TEST_ONLY面食店",40L),shop(2L,"TEST_ONLY快餐店",40L),shop(3L,"TEST_ONLY川菜店",40L)));
        when(profiles.selectList(any())).thenReturn(List.of(profile(1L,"面食",""),profile(2L,"快餐",""),profile(3L,"川菜,水煮鱼","")));
        when(reviews.selectList(any())).thenReturn(List.of());
        var engine=engine(shops,profiles,reviews,null);
        for(String food:List.of("面馆","炸鸡","酸菜鱼","韩式炸鸡","米线")){
            var c=new DiningCriteria(null,new DiningCriteria.CuisineCriteria(food,List.of()),null,null,null,DiningCriteria.SemanticPreferences.empty());
            var result=engine.retrieve(new SearchSpec(0,"test-food-evidence",ExecutionAction.SearchKind.RECOMMENDATIONS,3,c,List.of(),Set.of(),null,null));
            assertEquals(food.equals("面馆")?List.of(1L):List.of(),result.candidates().stream().map(x->x.getShopId()).toList(),food);
        }
    }
    @Test void cuisineAlternativesHardFilterIsOrBeforeSemanticRecall() {
        ShopMapper shops=mock(ShopMapper.class);AiShopProfileMapper profiles=mock(AiShopProfileMapper.class);
        AiReviewDocumentMapper reviews=mock(AiReviewDocumentMapper.class);SemanticShopRetriever semantic=mock(SemanticShopRetriever.class);
        when(shops.selectList(any())).thenReturn(List.of(shop(1L,"A",40L),shop(2L,"B",40L),shop(3L,"C",40L)));
        when(profiles.selectList(any())).thenReturn(List.of(profile(1L,"杭帮菜",""),profile(2L,"粤菜",""),profile(3L,"烧烤","")));
        when(reviews.selectList(any())).thenReturn(List.of());
        when(semantic.recall(anyString(),anyList(),anyMap(),anyMap())).thenReturn(SemanticRecallResult.unavailable());
        var criteria=new DiningCriteria(null,new DiningCriteria.CuisineCriteria(null,List.of(),List.of("杭帮菜","粤菜")),null,null,null,DiningCriteria.SemanticPreferences.empty());
        var result=engine(shops,profiles,reviews,semantic).retrieve(new SearchSpec(1,"new",ExecutionAction.SearchKind.RECOMMENDATIONS,3,criteria,List.of(),Set.of(),null,null));
        assertEquals(Set.of(1L,2L),result.candidates().stream().map(com.hmdp.ai.dto.DecisionRecommendation::getShopId).collect(java.util.stream.Collectors.toSet()));
        var whitelist=ArgumentCaptor.forClass(List.class);verify(semantic).recall(anyString(),whitelist.capture(),anyMap(),anyMap());
        assertEquals(2,whitelist.getValue().size());
        var unrelated=new com.hmdp.ai.dto.DecisionRecommendation();unrelated.setShopId(3L);unrelated.setCuisine("烧烤");
        var verified=new com.hmdp.ai.v2.verification.DeterministicResultVerifier().verify(new SearchSpec(1,"new",ExecutionAction.SearchKind.RECOMMENDATIONS,3,criteria,List.of(),Set.of(),null,null),List.of(unrelated));
        assertFalse(verified.hardChecksPassed());assertTrue(verified.verifiedCandidates().isEmpty());
    }
    @Test
    void keepsHardWhitelistSemanticRecallAndRelativePriceRerank() {
        ShopMapper shops = mock(ShopMapper.class);
        AiShopProfileMapper profiles = mock(AiShopProfileMapper.class);
        AiReviewDocumentMapper reviews = mock(AiReviewDocumentMapper.class);
        SemanticShopRetriever semantic = mock(SemanticShopRetriever.class);
        when(shops.selectList(any())).thenReturn(List.of(shop(1L, "贵但很匹配", 90L), shop(2L, "便宜", 50L)));
        when(profiles.selectList(any())).thenReturn(List.of(profile(1L, "烧烤", "夜景 热闹"), profile(2L, "烧烤", "夜景")));
        when(reviews.selectList(any())).thenReturn(List.of(review(1L, "江景夜景不错"), review(2L, "环境安静")));
        when(semantic.recall(any(), anyList(), anyMap(), anyMap())).thenAnswer(invocation -> {
            SemanticRecallResult result = new SemanticRecallResult();
            result.setAvailable(true); result.setShopScores(Map.of(1L, 0.7D, 2L, 0.45D));
            return result;
        });
        ShopRetrievalEngine engine = engine(shops, profiles, reviews, semantic);
        SearchSpec spec = new SearchSpec(3, "task", ExecutionAction.SearchKind.ALTERNATIVES, 3,
                new DiningCriteria(null, null, new DiningCriteria.BudgetCriteria(null, BigDecimal.valueOf(100)), null, null,
                        new DiningCriteria.SemanticPreferences(List.of(new DiningCriteria.SemanticPreference("夜景", DiningCriteria.Importance.PREFER)), List.of())),
                List.of(new RequirementChange.RelativePreference(DiningCriteria.PreferenceDimension.PRICE, RequirementChange.Direction.LOWER)),
                Set.of(3L), null, null);

        var result = engine.retrieve(spec);

        assertTrue(result.supported());
        assertTrue(result.metrics().semanticRetrievalUsed());
        assertEquals(2L, result.candidates().getFirst().getShopId(), "LOWER is an ephemeral rerank signal");
        verify(semantic).recall(contains("夜景"), anyList(), anyMap(), anyMap());
        ArgumentCaptor<QueryWrapper<Shop>> shopQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(shops).selectList(shopQuery.capture());
        String sql = shopQuery.getValue().getCustomSqlSegment().toLowerCase();
        assertTrue(sql.contains("avg_price") && sql.contains("<=") && sql.contains("not in"), sql);
        ArgumentCaptor<QueryWrapper<AiShopProfile>> profileQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(profiles).selectList(profileQuery.capture());
        assertTrue(profileQuery.getValue().getSqlSegment().toLowerCase().contains("shop_id"));
        assertFalse(profileQuery.getValue().getParamNameValuePairs().isEmpty());
        assertFalse(List.of(ShopRetrievalEngine.class.getDeclaredFields()).stream()
                .anyMatch(field -> field.getType().getSimpleName().contains("AiDecisionSession")));
    }

    @Test
    void similarUsesAnchorProfileAsSemanticQueryAndNeverFallsBackToOrdinaryRecommendation() {
        ShopMapper shops = mock(ShopMapper.class);
        AiShopProfileMapper profiles = mock(AiShopProfileMapper.class);
        AiReviewDocumentMapper reviews = mock(AiReviewDocumentMapper.class);
        SemanticShopRetriever semantic = mock(SemanticShopRetriever.class);
        when(shops.selectList(any())).thenReturn(List.of(shop(1L, "anchor", 50L), shop(2L, "similar", 55L), shop(3L, "other", 60L)));
        when(profiles.selectList(any())).thenReturn(List.of(profile(1L, "火锅", "热闹 夜景"), profile(2L, "烧烤", "热闹"), profile(3L, "素食", "安静")));
        when(reviews.selectList(any())).thenReturn(List.of());
        when(semantic.recall(any(), anyList(), anyMap(), anyMap())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            assertTrue(query.contains("火锅") && query.contains("热闹"));
            SemanticRecallResult result = new SemanticRecallResult(); result.setAvailable(true);
            result.setShopScores(Map.of(1L, 0.91D, 2L, 0.78D)); return result;
        });
        SearchSpec spec = new SearchSpec(1, "task", ExecutionAction.SearchKind.SIMILAR, 3, DiningCriteria.empty(), List.of(),
                Set.of(), new com.hmdp.ai.v2.grounding.GroundedReference.ShopIdentity(1L, "batch", 1), null);

        var result = engine(shops, profiles, reviews, semantic).retrieve(spec);

        assertTrue(result.supported());
        assertEquals(List.of(2L), result.candidates().stream().map(item -> item.getShopId()).toList());
        verify(semantic).recall(any(), anyList(), anyMap(), anyMap());
    }

    @Test
    void similarIsControlledUnsupportedWhenSemanticPrimitiveIsUnavailable() {
        ShopMapper shops = mock(ShopMapper.class);
        AiShopProfileMapper profiles = mock(AiShopProfileMapper.class);
        AiReviewDocumentMapper reviews = mock(AiReviewDocumentMapper.class);
        when(shops.selectList(any())).thenReturn(List.of(shop(1L, "anchor", 50L), shop(2L, "ordinary", 55L)));
        when(profiles.selectList(any())).thenReturn(List.of(profile(1L, "火锅", "热闹"), profile(2L, "烧烤", "安静")));
        when(reviews.selectList(any())).thenReturn(List.of());
        SearchSpec spec = new SearchSpec(1, "task", ExecutionAction.SearchKind.SIMILAR, 3, DiningCriteria.empty(), List.of(),
                Set.of(), new com.hmdp.ai.v2.grounding.GroundedReference.ShopIdentity(1L, "batch", 1), null);

        var result = engine(shops, profiles, reviews, null).retrieve(spec);

        assertFalse(result.supported());
        assertTrue(result.candidates().isEmpty());
        verify(shops).selectList(any());
    }

    private ShopRetrievalEngine engine(ShopMapper shops, AiShopProfileMapper profiles,
                                       AiReviewDocumentMapper reviews, SemanticShopRetriever semantic) {
        ShopRetrievalEngine engine = new ShopRetrievalEngine();
        ReflectionTestUtils.setField(engine, "shopMapper", shops);
        ReflectionTestUtils.setField(engine, "profileMapper", profiles);
        ReflectionTestUtils.setField(engine, "reviewMapper", reviews);
        ReflectionTestUtils.setField(engine, "semanticShopRetriever", semantic);
        ReflectionTestUtils.setField(engine, "semanticWeight", 18D);
        return engine;
    }
    private Shop shop(Long id, String name, Long price) {
        Shop shop = new Shop(); shop.setId(id); shop.setName(name); shop.setAvgPrice(price); shop.setScore(45);
        shop.setCity("福州市"); shop.setOpenHours("00:00-23:59"); shop.setX(119.3D); shop.setY(26.1D); return shop;
    }
    private AiShopProfile profile(Long id, String cuisine, String tags) {
        AiShopProfile profile = new AiShopProfile(); profile.setShopId(id); profile.setCuisine(cuisine);
        profile.setSceneTags(tags); profile.setAmbienceTags(""); profile.setSummary(tags); return profile;
    }
    private AiReviewDocument review(Long id, String content) {
        AiReviewDocument review = new AiReviewDocument(); review.setShopId(id); review.setContent(content); return review;
    }
}
