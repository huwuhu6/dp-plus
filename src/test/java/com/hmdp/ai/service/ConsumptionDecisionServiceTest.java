package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
import com.hmdp.ai.dto.DecisionMetrics;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.entity.AiDecisionSession;
import com.hmdp.ai.entity.AiDecisionMetric;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.mapper.AiDecisionMessageMapper;
import com.hmdp.ai.mapper.AiDecisionMetricMapper;
import com.hmdp.ai.mapper.AiDecisionSessionMapper;
import com.hmdp.ai.mapper.AiDecisionStepMapper;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsumptionDecisionServiceTest {
    private ConsumptionDecisionService service;
    private ConstraintExtractor constraintExtractor;
    private ShopMapper shopMapper;
    private AiShopProfileMapper profileMapper;
    private AiDecisionSessionMapper sessionMapper;
    private AiDecisionMetricMapper metricMapper;
    private AiDecisionMessageMapper messageMapper;
    private AiReviewDocumentMapper reviewMapper;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        service = new ConsumptionDecisionService();
        constraintExtractor = mock(ConstraintExtractor.class);
        shopMapper = mock(ShopMapper.class);
        profileMapper = mock(AiShopProfileMapper.class);
        sessionMapper = mock(AiDecisionSessionMapper.class);
        metricMapper = mock(AiDecisionMetricMapper.class);

        ReflectionTestUtils.setField(service, "constraintExtractor", constraintExtractor);
        ReflectionTestUtils.setField(service, "modelCallTracker", new AiModelCallTracker());
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
        ResultEvaluationService resultEvaluationService = new ResultEvaluationService();
        ReflectionTestUtils.setField(resultEvaluationService, "aiProperties", new AiProperties());
        ReflectionTestUtils.setField(service, "resultEvaluationService", resultEvaluationService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(service, "profileMapper", profileMapper);
        reviewMapper = mock(AiReviewDocumentMapper.class);
        ReflectionTestUtils.setField(service, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "stepMapper", mock(AiDecisionStepMapper.class));
        ReflectionTestUtils.setField(service, "metricMapper", metricMapper);
        messageMapper = mock(AiDecisionMessageMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);

        doAnswer(invocation -> {
            AiDecisionSession session = invocation.getArgument(0);
            session.setId(100L);
            return 1;
        }).when(sessionMapper).insert(any(AiDecisionSession.class));
        when(metricMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L, 1L);
        when(sessionMapper.update(any(AiDecisionSession.class), any(UpdateWrapper.class))).thenReturn(1);
    }

    @Test
    void rejectsAccessToAnotherUsersSession() {
        AiDecisionSession session = new AiDecisionSession();
        session.setId(100L);
        session.setUserId(1L);
        session.setStatus("COMPLETED");
        when(sessionMapper.selectById(100L)).thenReturn(session);
        UserDTO otherUser = new UserDTO();
        otherUser.setId(2L);
        UserHolder.saveUser(otherUser);

        SecurityException error = assertThrows(SecurityException.class, () -> service.getDecision(100L));

        assertEquals("无权访问其他用户的决策会话", error.getMessage());
    }

    @Test
    void normalizesUnspecifiedEveningToNineteenRegardlessOfModelGuess() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setArrivalTime("18:00");
        DecisionRequest request = new DecisionRequest();
        request.setQuery("晚上想吃西餐");

        ReflectionTestUtils.invokeMethod(service, "reconcileRequestFacts", constraints, request);

        assertEquals("19:00", constraints.getArrivalTime());
    }

    @Test
    void preservesExplicitEveningTime() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setArrivalTime("20:00");
        DecisionRequest request = new DecisionRequest();
        request.setQuery("晚上20:00吃西餐");

        ReflectionTestUtils.invokeMethod(service, "reconcileRequestFacts", constraints, request);

        assertEquals("20:00", constraints.getArrivalTime());
    }

    @Test
    void derivesDateIntentAndUsesSceneTagForRanking() {
        DecisionConstraints constraints = new DecisionConstraints();
        DecisionRequest request = new DecisionRequest();
        request.setQuery("和女朋友吃西餐");
        ReflectionTestUtils.invokeMethod(service, "reconcileRequestFacts", constraints, request);
        assertEquals("约会", constraints.getOccasion());

        Shop shop = new Shop();
        shop.setId(4L);
        shop.setName("测试西餐厅");
        shop.setScore(40);
        AiShopProfile profile = new AiShopProfile();
        profile.setShopId(4L);
        profile.setSceneTags("约会,纪念日");
        DecisionRecommendation recommendation = ReflectionTestUtils.invokeMethod(service, "toRecommendation",
                shop, profile, (List<?>) Collections.emptyList(), request, constraints);

        assertTrue(recommendation.getMatchedReasons().contains("场景标签包含约会"));
    }

    @Test
    void acceptsCityNamesWithOrWithoutTheMunicipalitySuffix() {
        assertEquals(Arrays.asList("福州", "福州市"),
                ReflectionTestUtils.invokeMethod(service, "administrativeNameAliases", "福州"));
        assertEquals(Arrays.asList("杭州市", "杭州"),
                ReflectionTestUtils.invokeMethod(service, "administrativeNameAliases", "杭州市"));
    }

    @Test
    void recommendationEvidenceDoesNotExposeInternalSourceType() {
        Shop shop = new Shop();
        shop.setId(4L);
        shop.setName("test shop");
        AiReviewDocument document = new AiReviewDocument();
        document.setSourceType("LOCAL_DEMO");
        document.setContent("Evidence body");

        DecisionRecommendation recommendation = ReflectionTestUtils.invokeMethod(service, "toRecommendation",
                shop, null, Arrays.asList(document), new DecisionRequest(), new DecisionConstraints());

        assertEquals(Arrays.asList("Evidence body"), recommendation.getEvidence());
    }

    @Test
    void nearbyWithoutCoordinatesPausesForLocation() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setNearby(true);
        when(constraintExtractor.extract("找附近的火锅")).thenReturn(constraints);

        DecisionRequest request = new DecisionRequest();
        request.setQuery("找附近的火锅");
        DecisionResponse response = service.decide(request);

        assertEquals("CLARIFYING", response.getStatus());
        assertEquals("PROVIDE_LOCATION", response.getOptions().get(0).getId());
        assertEquals(0, response.getMetrics().getModelCallCount());
        verify(shopMapper, never()).selectList(any());
        verify(metricMapper).insert(any(AiDecisionMetric.class));
    }

    @Test
    void restaurantRecommendationDefaultsToLocationClarification() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("日料");
        when(constraintExtractor.extract("推荐一家日料")).thenReturn(constraints);

        DecisionRequest request = new DecisionRequest();
        request.setQuery("推荐一家日料");
        DecisionResponse response = service.decide(request);

        assertEquals("CLARIFYING", response.getStatus());
        assertTrue(response.getQuestion().contains("实际位置"));
        verify(shopMapper, never()).selectList(any());
    }

    @Test
    void namedPlaceDoesNotResolveToHardcodedCoordinates() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("火锅");
        when(constraintExtractor.extract("帮我看看鼓楼的火锅")).thenReturn(constraints);

        DecisionRequest request = new DecisionRequest();
        request.setQuery("帮我看看鼓楼的火锅");
        DecisionResponse response = service.decide(request);

        assertEquals("CLARIFYING", response.getStatus());
        assertTrue(response.getQuestion().contains("地理编码服务"));
        assertEquals(3, response.getOptions().size());
        verify(shopMapper, never()).selectList(any());
    }

    @Test
    void explicitCityAndAreaIsNotMappedWithoutGeocodingService() {
        DecisionRequest request = new DecisionRequest();
        request.setQuery("帮我看看福州鼓楼的火锅");
        DecisionConstraints constraints = new DecisionConstraints();

        ReflectionTestUtils.invokeMethod(service, "reconcileRequestFacts", constraints, request);

        assertNull(request.getLatitude());
        assertNull(request.getLongitude());
        assertFalse(request.getUseLocationScope());
    }

    @Test
    void treatsBarbecueAndGrilledMeatAsOneCuisineFamily() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("烧烤");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("烤肉,韩式");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertTrue(matched);
    }

    @Test
    void 用户日本料理匹配商户日料() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("日本料理");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("日料");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertTrue(matched);
    }

    @Test
    void 用户日式料理匹配商户日料() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("日式料理");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("日料");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertTrue(matched);
    }

    @Test
    void 用户日本菜匹配商户日料寿司逗号分隔() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("日本菜");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("日料,寿司");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertTrue(matched);
    }

    @Test
    void 用户寿司匹配商户日料() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("寿司");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("日料");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertTrue(matched);
    }

    @Test
    void 用户日料匹配商户日本料理() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("日料");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("日本料理");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertTrue(matched);
    }

    @Test
    void 用户烤肉匹配商户火锅烤肉逗号分隔() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("烤肉");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("火锅,烤肉");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertTrue(matched);
    }

    @Test
    void 用户牛排匹配商户西餐() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("牛排");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("西餐");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertTrue(matched);
    }

    @Test
    void 不同菜系不应错误匹配() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("川菜");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("湘菜");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertFalse(matched);
    }

    @Test
    void 无关菜系不应错误匹配() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("日料");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("粤菜");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertFalse(matched);
    }

    @Test
    void 用户港式茶餐厅匹配商户港式茶餐厅() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("港式茶餐厅");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("港式茶餐厅");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertTrue(matched);
    }

    @Test
    void 复合cuisine的空格处理正确() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("日料");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine(" 日料 , 寿司 ");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, profile,
                new DecisionRequest(), constraints);

        assertTrue(matched);
    }

    @Test
    void 用户日料匹配商户nullProfile不匹配() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("日料");
        Shop shop = new Shop();
        shop.setOpenHours("10:00-22:00");

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesHardConstraints", shop, null,
                new DecisionRequest(), constraints);

        assertFalse(matched);
    }

    @Test
    void lightTasteOnlyReturnsShopsWithSupportingReviewEvidence() {
        DecisionConstraints constraints = new DecisionConstraints();
        when(constraintExtractor.extract("我想吃点清淡的")).thenReturn(constraints);
        Shop lightShop = new Shop();
        lightShop.setId(1L);
        lightShop.setName("清淡面馆");
        lightShop.setScore(40);
        lightShop.setOpenHours("10:00-22:00");
        Shop grilledShop = new Shop();
        grilledShop.setId(2L);
        grilledShop.setName("烤肉店");
        grilledShop.setScore(50);
        grilledShop.setOpenHours("10:00-22:00");
        when(shopMapper.selectList(any())).thenReturn(Arrays.asList(lightShop, grilledShop));
        when(profileMapper.selectList(any())).thenReturn(Collections.emptyList());
        AiReviewDocument lightEvidence = new AiReviewDocument();
        lightEvidence.setShopId(1L);
        lightEvidence.setSourceType("TEST");
        lightEvidence.setContent("汤头清淡不油腻，适合晚餐。");
        AiReviewDocument grilledEvidence = new AiReviewDocument();
        grilledEvidence.setShopId(2L);
        grilledEvidence.setSourceType("TEST");
        grilledEvidence.setContent("五花肉很香，建议提前排队。");
        when(reviewMapper.selectList(any())).thenReturn(Arrays.asList(lightEvidence, grilledEvidence));

        DecisionRequest request = new DecisionRequest();
        request.setQuery("我想吃点清淡的");
        request.setLocationStatus("DECLINED");
        DecisionResponse response = service.decide(request);

        assertEquals("COMPLETED", response.getStatus());
        assertEquals(1, response.getRecommendations().size());
        assertEquals(1L, response.getRecommendations().get(0).getShopId());
        assertTrue(response.getRecommendations().get(0).getMatchedReasons().contains("评价证据表明口味清淡"));
    }

    @Test
    void savedLocationScopeEnforcesNearbyRadiusForContextualNewDecision() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setOccasion("约会");
        constraints.setQuiet(true);
        when(constraintExtractor.extract("还有没有适合约会且安静的地方")).thenReturn(constraints);
        when(shopMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(profileMapper.selectList(any())).thenReturn(Collections.emptyList());

        DecisionRequest request = new DecisionRequest();
        request.setQuery("还有没有适合约会且安静的地方");
        request.setLatitude(26.054D);
        request.setLongitude(119.186D);
        request.setLocationStatus("AVAILABLE");
        request.setUseLocationScope(true);
        DecisionResponse response = service.decide(request);

        assertEquals("WAITING_RELAXATION", response.getStatus());
        assertTrue(response.getConstraints().getNearby());
        assertEquals(5D, response.getConstraints().getRadiusKm());
        assertTrue(response.getRelaxation().getAutomatic());
        assertTrue(response.getConstraints().getSoftPreferences().contains("已按会话位置在附近检索"));
    }

    @Test
    void namedCityWithoutRelaxableConstraintsReturnsNoDataInsteadOfRelaxation() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setTargetCity("重庆");
        when(constraintExtractor.extract("重庆有什么好吃的")).thenReturn(constraints);
        when(shopMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(profileMapper.selectList(any())).thenReturn(Collections.emptyList());

        DecisionRequest request = new DecisionRequest();
        request.setQuery("重庆有什么好吃的");
        request.setCity("重庆");
        request.setLocationStatus("RESOLVED_BY_NAME");
        DecisionResponse response = service.decide(request);

        assertEquals("ZERO_RESULT_NO_DATA", response.getStatus());
        assertEquals("SWITCH_CITY", response.getOptions().get(0).getId());
        assertEquals("END_DECISION", response.getOptions().get(1).getId());
        assertTrue(response.getQuestion().contains("重庆目前暂无收录"));
    }

    @Test
    void neverOffersOrAppliesBudgetIncreaseWhenRelativeCheaperBudgetIsLocked() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setBudgetPerPerson(64);
        constraints.setRadiusKm(3D);
        constraints.getLockedConstraints().add("budgetPerPerson");
        when(shopMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(profileMapper.selectList(any())).thenReturn(Collections.emptyList());

        DecisionRequest request = new DecisionRequest();
        request.setQuery("\u66f4\u4fbf\u5b9c\u70b9");
        request.setLatitude(26.08D);
        request.setLongitude(119.30D);
        request.setLocationStatus("AVAILABLE");
        DecisionResponse paused = service.decide(request, constraints);

        assertEquals("WAITING_RELAXATION", paused.getStatus());
        assertTrue(paused.getOptions().stream().anyMatch(item -> "EXPAND_RADIUS".equals(item.getId())));
        assertFalse(paused.getOptions().stream().anyMatch(item -> "INCREASE_BUDGET".equals(item.getId())));
    }

    @Test
    void relaxationResumesWithoutExtractingConstraintsAgain() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("不存在菜系");
        when(constraintExtractor.extract("想吃不存在菜系")).thenReturn(constraints);

        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("测试餐厅");
        shop.setAvgPrice(80L);
        shop.setScore(40);
        shop.setOpenHours("10:00-22:00");
        shop.setX(120.0D);
        shop.setY(30.0D);
        AiShopProfile profile = new AiShopProfile();
        profile.setShopId(1L);
        profile.setCuisine("火锅");
        when(shopMapper.selectList(any())).thenReturn(Collections.singletonList(shop));
        when(profileMapper.selectList(any())).thenReturn(Collections.singletonList(profile));

        DecisionRequest request = new DecisionRequest();
        request.setQuery("想吃不存在菜系");
        request.setLocationStatus("DECLINED");
        DecisionResponse paused = service.decide(request);
        assertEquals("WAITING_RELAXATION", paused.getStatus());
        assertEquals("RELAX_CUISINE", paused.getOptions().get(0).getId());

        ArgumentCaptor<AiDecisionSession> sessionCaptor = ArgumentCaptor.forClass(AiDecisionSession.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        when(sessionMapper.selectById(100L)).thenReturn(sessionCaptor.getValue());

        DecisionFollowUpRequest followUp = new DecisionFollowUpRequest();
        followUp.setSelectedOptionId("RELAX_CUISINE");
        DecisionResponse completed = service.continueDecision(100L, followUp);

        assertEquals("COMPLETED", completed.getStatus());
        assertEquals(1, completed.getRecommendations().size());
        assertTrue(completed.getAnswer().contains("测试餐厅"));
        assertEquals(1, completed.getMetrics().getRelaxationCount());
        verify(constraintExtractor).extract("想吃不存在菜系");
        verify(metricMapper, org.mockito.Mockito.times(2)).insert(any(AiDecisionMetric.class));
    }

    @Test
    void expandsDefaultNearbyRadiusAfterLocationWasProvided() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setNearby(true);
        when(constraintExtractor.extract("\u627e\u9644\u8fd1\u7684\u706b\u9505")).thenReturn(constraints);
        when(shopMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(profileMapper.selectList(any())).thenReturn(Collections.emptyList());

        DecisionRequest request = new DecisionRequest();
        request.setQuery("\u627e\u9644\u8fd1\u7684\u706b\u9505");
        assertEquals("CLARIFYING", service.decide(request).getStatus());

        ArgumentCaptor<AiDecisionSession> sessionCaptor = ArgumentCaptor.forClass(AiDecisionSession.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        AiDecisionSession session = sessionCaptor.getValue();
        when(sessionMapper.selectById(100L)).thenReturn(session);

        DecisionFollowUpRequest provideLocation = new DecisionFollowUpRequest();
        provideLocation.setSelectedOptionId("PROVIDE_LOCATION");
        provideLocation.setLatitude(26.08D);
        provideLocation.setLongitude(119.30D);
        DecisionResponse paused = service.continueDecision(100L, provideLocation);

        assertEquals("WAITING_RELAXATION", paused.getStatus());
        assertEquals("EXPAND_RADIUS", paused.getOptions().get(0).getId());
        assertEquals(5D, paused.getConstraints().getRadiusKm());
        assertTrue(paused.getRelaxation().getAutomatic());

        DecisionFollowUpRequest expandRadius = new DecisionFollowUpRequest();
        expandRadius.setSelectedOptionId("EXPAND_RADIUS");
        DecisionResponse retried = service.continueDecision(100L, expandRadius);

        assertEquals("WAITING_RELAXATION", retried.getStatus());
        assertEquals(7D, retried.getConstraints().getRadiusKm());
    }

    @Test
    void automaticallyExpandsOnlyDefaultNearbyRadiusBeforeAskingForUserRelaxation() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("火锅");
        constraints.setNearby(true);
        when(constraintExtractor.extract("附近的火锅")).thenReturn(constraints);

        Shop shop = new Shop();
        shop.setId(77L);
        shop.setName("四公里火锅店");
        shop.setScore(45);
        shop.setAvgPrice(100L);
        shop.setOpenHours("10:00-22:00");
        shop.setX(119.1934D);
        shop.setY(26.0778D);
        AiShopProfile profile = new AiShopProfile();
        profile.setShopId(77L);
        profile.setCuisine("火锅");
        when(shopMapper.selectList(any())).thenReturn(Collections.singletonList(shop));
        when(profileMapper.selectList(any())).thenReturn(Collections.singletonList(profile));
        when(reviewMapper.selectList(any())).thenReturn(Collections.emptyList());

        DecisionRequest request = new DecisionRequest();
        request.setQuery("附近的火锅");
        request.setLatitude(26.0400D);
        request.setLongitude(119.2000D);
        request.setLocationStatus("AVAILABLE");
        request.setUseLocationScope(true);
        DecisionResponse response = service.decide(request);

        assertEquals("COMPLETED", response.getStatus());
        assertEquals(0, response.getRelaxation().getStrictCandidateCount());
        assertEquals(1, response.getRelaxation().getRelaxedCandidateCount());
        assertTrue(response.getRelaxation().getAutomatic());
        assertEquals(5D, response.getConstraints().getRadiusKm());
        assertTrue(response.getAnswer().contains("不改变地点、菜系、预算和到店时间等硬条件"));
    }

    @Test
    void concurrentFollowUpIsRejectedWhenAnotherRequestAlreadyClaimedTheSession() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setNearby(true);
        when(constraintExtractor.extract("找附近的火锅")).thenReturn(constraints);

        DecisionRequest request = new DecisionRequest();
        request.setQuery("找附近的火锅");
        service.decide(request);

        ArgumentCaptor<AiDecisionSession> sessionCaptor = ArgumentCaptor.forClass(AiDecisionSession.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        when(sessionMapper.selectById(100L)).thenReturn(sessionCaptor.getValue());
        when(sessionMapper.update(any(AiDecisionSession.class), any(UpdateWrapper.class))).thenReturn(0);

        DecisionFollowUpRequest followUp = new DecisionFollowUpRequest();
        followUp.setLatitude(30.2741D);
        followUp.setLongitude(120.1551D);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.continueDecision(100L, followUp));

        assertTrue(error.getMessage().contains("已被其他续聊请求处理"));
        verify(shopMapper, never()).selectList(any());
        verify(constraintExtractor).extract("找附近的火锅");
    }

    @Test
    void decliningLocationSearchesCityWideWithoutKeepingLocationConstraint() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setNearby(true);
        constraints.setCuisine("日料");
        constraints.getHardConstraints().add("附近");
        constraints.getHardConstraints().add("日料");
        constraints.getMissingInformation().add("具体位置/所在地点");
        when(constraintExtractor.extract("找附近的日料")).thenReturn(constraints);

        DecisionRequest request = new DecisionRequest();
        request.setQuery("找附近的日料");
        DecisionResponse paused = service.decide(request);
        assertEquals("CLARIFYING", paused.getStatus());

        ArgumentCaptor<AiDecisionSession> sessionCaptor = ArgumentCaptor.forClass(AiDecisionSession.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        when(sessionMapper.selectById(100L)).thenReturn(sessionCaptor.getValue());

        Shop shop = new Shop();
        shop.setId(8L);
        shop.setName("测试寿司店");
        shop.setAvgPrice(88L);
        shop.setScore(40);
        shop.setOpenHours("10:00-22:00");
        AiShopProfile profile = new AiShopProfile();
        profile.setShopId(8L);
        profile.setCuisine("日料,寿司");
        when(shopMapper.selectList(any())).thenReturn(Collections.singletonList(shop));
        when(profileMapper.selectList(any())).thenReturn(Collections.singletonList(profile));

        DecisionFollowUpRequest followUp = new DecisionFollowUpRequest();
        followUp.setSelectedOptionId("DECLINE_LOCATION");
        DecisionResponse completed = service.continueDecision(100L, followUp);

        assertEquals("COMPLETED", completed.getStatus());
        assertFalse(completed.getConstraints().getNearby());
        assertEquals(-1D, completed.getConstraints().getRadiusKm());
        assertFalse(completed.getConstraints().getHardConstraints().contains("附近"));
        assertFalse(completed.getConstraints().getMissingInformation().contains("具体位置/所在地点"));
        assertTrue(completed.getConstraints().getSoftPreferences().contains("用户未提供位置，按全城搜索"));
    }

    @Test
    void unrelatedFollowUpCancelsPausedSessionWithoutRunningAnotherSearch() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("不存在菜系");
        when(constraintExtractor.extract("想吃不存在菜系")).thenReturn(constraints);
        when(shopMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(profileMapper.selectList(any())).thenReturn(Collections.emptyList());

        DecisionRequest request = new DecisionRequest();
        request.setQuery("想吃不存在菜系");
        request.setLocationStatus("DECLINED");
        DecisionResponse paused = service.decide(request);
        assertEquals("WAITING_RELAXATION", paused.getStatus());

        ArgumentCaptor<AiDecisionSession> sessionCaptor = ArgumentCaptor.forClass(AiDecisionSession.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        when(sessionMapper.selectById(100L)).thenReturn(sessionCaptor.getValue());

        DecisionFollowUpRequest followUp = new DecisionFollowUpRequest();
        followUp.setMessage("算了，我想问天气");
        DecisionResponse cancelled = service.continueDecision(100L, followUp);

        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals("已结束本次推荐。需要新的消费建议时，请发起新的请求。", cancelled.getAnswer());
        assertEquals("CANCELLED", sessionCaptor.getValue().getStatus());
        verify(shopMapper, times(1)).selectList(any());
    }

    @Test
    void exposesRuleFallbackWhenModelExtractionFails() {
        DecisionResponse response = new DecisionResponse();
        DecisionMetrics metrics = new DecisionMetrics();
        metrics.setModelCallCount(1);
        metrics.setModelFailureCount(1);

        ReflectionTestUtils.invokeMethod(service, "populateModelUsage", response, metrics);

        assertFalse(response.getUsedModel());
        assertEquals("模型约束提取未成功，本次已使用本地规则继续处理。", response.getDegradedReason());
    }

    @Test
    void rankingOrderIsDeterminedByDifferentiatedFactorsNotConstantBonuses() {
        // Both shops match cuisine 火锅 and budget 100, so cuisine (+20) and light taste (+12)
        // are constant bonuses removed. The ordering must be based on differentiated factors:
        // base rating, budget differentiation, occasion, quiet, queue, evidence.
        Shop shopA = new Shop();
        shopA.setId(1L);
        shopA.setName("高分餐厅");
        shopA.setScore(50);
        shopA.setAvgPrice(60L);
        shopA.setOpenHours("10:00-22:00");

        Shop shopB = new Shop();
        shopB.setId(2L);
        shopB.setName("低分餐厅");
        shopB.setScore(30);
        shopB.setAvgPrice(90L);
        shopB.setOpenHours("10:00-22:00");

        AiShopProfile profile = new AiShopProfile();
        profile.setShopId(1L);
        profile.setCuisine("火锅");
        profile.setSceneTags("约会");
        profile.setAmbienceTags("安静");
        profile.setQueueLevel("LOW");

        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setCuisine("火锅");
        constraints.setBudgetPerPerson(100);
        constraints.setOccasion("约会");
        constraints.setQuiet(true);
        constraints.setAvoidQueue(true);

        DecisionRequest request = new DecisionRequest();

        // Shop A: base=50/50*20=20, budget=20*(1-60/100*0.3)=16.4, occasion=+12, quiet=+12, queue=+8 = 68.4
        DecisionRecommendation recA = ReflectionTestUtils.invokeMethod(service, "toRecommendation",
                shopA, profile, Collections.emptyList(), request, constraints);
        // Shop B: base=30/50*20=12, budget=20*(1-90/100*0.3)=14.6, occasion=+12, quiet=+12, queue=+8 = 58.6
        DecisionRecommendation recB = ReflectionTestUtils.invokeMethod(service, "toRecommendation",
                shopB, profile, Collections.emptyList(), request, constraints);

        assertTrue(recA.getScore() > recB.getScore(),
                "High-rated shop should rank higher than low-rated shop based on differentiated factors");
        assertTrue(recA.getMatchedReasons().contains("菜系：火锅"),
                "Cuisine matchedReasons should be preserved even without score bonus");
        assertFalse(recA.getMatchedReasons().stream().anyMatch(r -> r.contains("口味清淡")),
                "No light taste matchedReasons when constraint is not set");
    }

    @Test
    void semanticRetrievalQueryReturnsEmptyWhenQueryIsOnlyGeographicToken() {
        DecisionConstraints constraints = new DecisionConstraints();
        DecisionRequest request = new DecisionRequest();
        request.setQuery("重庆");
        request.setCity("重庆");

        String query = ReflectionTestUtils.invokeMethod(service, "semanticRetrievalQuery",
                request, constraints);

        // After removing the city token "重庆", the query is empty.
        // The original code fell back to request.getQuery() ("重庆"), which contains
        // geographic tokens. After the fix, it returns empty string to let the
        // MilvusSemanticShopRetriever guard clause handle it.
        assertEquals("", query,
                "semanticRetrievalQuery should return empty when query is only geographic tokens");
    }

    @Test
    void semanticRetrievalQueryFallsBackToKeywordWhenQueryIsOnlyGeographic() {
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setKeyword("火锅");
        DecisionRequest request = new DecisionRequest();
        request.setQuery("重庆");
        request.setCity("重庆");

        String query = ReflectionTestUtils.invokeMethod(service, "semanticRetrievalQuery",
                request, constraints);

        // Keyword "火锅" should be preserved as the fallback semantic query
        assertEquals("火锅", query,
                "semanticRetrievalQuery should fall back to keyword when available");
    }

    @Test
    void exposesLocalRuleModeWhenModelIsNotConfigured() {
        DecisionResponse response = new DecisionResponse();
        DecisionMetrics metrics = new DecisionMetrics();

        ReflectionTestUtils.invokeMethod(service, "populateModelUsage", response, metrics);

        assertFalse(response.getUsedModel());
        assertEquals("未配置模型服务，本次已使用本地规则处理。", response.getDegradedReason());
    }
}
