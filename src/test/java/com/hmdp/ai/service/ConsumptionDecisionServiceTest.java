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

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(service, "profileMapper", profileMapper);
        ReflectionTestUtils.setField(service, "reviewMapper", mock(AiReviewDocumentMapper.class));
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
        when(metricMapper.selectCount(any(QueryWrapper.class))).thenReturn(0, 1);
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
        verify(metricMapper).insert(any());
    }

    @Test
    void rejectsCapabilityQuestionBeforeCreatingDecisionSession() {
        DecisionRequest request = new DecisionRequest();
        request.setQuery("你是？");

        DecisionResponse response = service.decide(request);

        assertEquals("UNSUPPORTED", response.getStatus());
        assertTrue(response.getAnswer().contains("点评消费决策助手"));
        assertEquals(Boolean.FALSE, response.getUsedModel());
        verify(sessionMapper, never()).insert(any(AiDecisionSession.class));
        verify(constraintExtractor, never()).extract(any());
        verify(shopMapper, never()).selectList(any());
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
        verify(metricMapper, org.mockito.Mockito.times(2)).insert(any());
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
        verify(messageMapper, org.mockito.Mockito.times(2)).insert(any());
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
        verify(messageMapper, times(4)).insert(any());
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
    void exposesLocalRuleModeWhenModelIsNotConfigured() {
        DecisionResponse response = new DecisionResponse();
        DecisionMetrics metrics = new DecisionMetrics();

        ReflectionTestUtils.invokeMethod(service, "populateModelUsage", response, metrics);

        assertFalse(response.getUsedModel());
        assertEquals("未配置模型服务，本次已使用本地规则处理。", response.getDegradedReason());
    }
}
