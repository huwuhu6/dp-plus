package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
import com.hmdp.ai.dto.DecisionMetrics;
import com.hmdp.ai.dto.DecisionOption;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.DecisionTraceItem;
import com.hmdp.ai.entity.AiDecisionSession;
import com.hmdp.ai.entity.AiDecisionStep;
import com.hmdp.ai.entity.AiDecisionMetric;
import com.hmdp.ai.entity.AiDecisionMessage;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.mapper.AiDecisionSessionMapper;
import com.hmdp.ai.mapper.AiDecisionStepMapper;
import com.hmdp.ai.mapper.AiDecisionMetricMapper;
import com.hmdp.ai.mapper.AiDecisionMessageMapper;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ConsumptionDecisionService {
    private static final Logger log = LoggerFactory.getLogger(ConsumptionDecisionService.class);
    private static final Pattern EVENING_WITH_EXPLICIT_TIME = Pattern.compile("(?:晚上|晚)\\s*\\d{1,2}(?::\\d{2})?(?:点)?");
    private static final Map<String, double[]> DEMO_PLACE_COORDINATES = demoPlaceCoordinates();
    @Resource private ConstraintExtractor constraintExtractor;
    @Resource private AiModelCallTracker modelCallTracker;
    @Resource private OpenAiCompatibleClient aiClient;
    @Resource private AiProperties aiProperties;
    @Resource private ObjectMapper objectMapper;
    @Resource private ShopMapper shopMapper;
    @Resource private AiShopProfileMapper profileMapper;
    @Resource private AiReviewDocumentMapper reviewMapper;
    @Resource private AiDecisionSessionMapper sessionMapper;
    @Resource private AiDecisionStepMapper stepMapper;
    @Resource private AiDecisionMetricMapper metricMapper;
    @Resource private AiDecisionMessageMapper messageMapper;

    public DecisionResponse decide(DecisionRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        log.info("[AI][decision] event=REQUEST_RECEIVED query={} latitude={} longitude={} maxCandidates={}",
                compact(request.getQuery()), request.getLatitude(), request.getLongitude(), request.getMaxCandidates());
        AiDecisionSession session = new AiDecisionSession();
        session.setUserId(UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
        session.setQueryText(request.getQuery().trim());
        session.setStatus("CREATED");
        try {
            session.setRequestContextJson(objectMapper.writeValueAsString(request));
        } catch (Exception e) {
            throw new IllegalStateException("请求上下文无法保存", e);
        }
        sessionMapper.insert(session);
        saveMessage(session.getId(), "USER", "INITIAL_QUERY", request.getQuery());
        log.info("[AI][session={}] state=CREATED action=SESSION_CREATED", session.getId());
        return execute(session, request, null, true, false);
    }

    public DecisionResponse continueDecision(Long sessionId, DecisionFollowUpRequest followUp) {
        AiDecisionSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new IllegalArgumentException("决策记录不存在");
        ensureSessionOwner(session);
        if (!"CLARIFYING".equals(session.getStatus()) && !"WAITING_RELAXATION".equals(session.getStatus())) {
            throw new IllegalArgumentException("当前决策不需要补充信息或放宽条件");
        }
        log.info("[AI][session={}] state={} action=FOLLOW_UP_RECEIVED selectedOptionId={} hasLatitude={} hasLongitude={} message={}",
                sessionId, session.getStatus(), followUp == null ? null : followUp.getSelectedOptionId(),
                followUp != null && followUp.getLatitude() != null, followUp != null && followUp.getLongitude() != null,
                followUp == null ? "" : compact(followUp.getMessage()));
        try {
            if (isEndRequest(followUp)) return cancelPausedDecision(session, followUp);
            DecisionRequest request = objectMapper.readValue(session.getRequestContextJson(), DecisionRequest.class);
            DecisionConstraints constraints = objectMapper.readValue(session.getConstraintsJson(), DecisionConstraints.class);
            String pausedStatus = session.getStatus();
            boolean resumedWithRelaxation = "WAITING_RELAXATION".equals(pausedStatus);
            if ("CLARIFYING".equals(pausedStatus)) {
                if ("DECLINE_LOCATION".equals(followUp == null ? null : followUp.getSelectedOptionId())) {
                    log.info("[AI][session={}] state=CLARIFYING action=LOCATION_DECLINED searchScope=CITYWIDE", sessionId);
                    request.setLocationStatus("DECLINED");
                    constraints.setNearby(false);
                    constraints.setRadiusKm(-1D);
                    constraints.getSoftPreferences().add("用户未提供位置，按全城搜索");
                    removeHardConstraints(constraints, "附近", "距离", "位置");
                    removeMissingInformation(constraints, "位置", "坐标", "起点");
                } else if (followUp == null || followUp.getLatitude() == null || followUp.getLongitude() == null) {
                    throw new IllegalArgumentException("请提供 latitude 和 longitude 后继续附近搜索");
                } else {
                    request.setLatitude(followUp.getLatitude());
                    request.setLongitude(followUp.getLongitude());
                    request.setLocationStatus("AVAILABLE");
                    log.info("[AI][session={}] state=CLARIFYING action=LOCATION_ACCEPTED latitude={} longitude={}",
                            sessionId, request.getLatitude(), request.getLongitude());
                }
            } else {
                if (followUp == null || followUp.getSelectedOptionId() == null) {
                    throw new IllegalArgumentException("请使用 selectedOptionId 选择一个放宽方案");
                }
                applyRelaxation(constraints, followUp.getSelectedOptionId());
                log.info("[AI][session={}] state=WAITING_RELAXATION action=RELAXATION_SELECTED option={}",
                        sessionId, followUp.getSelectedOptionId());
            }
            session.setRequestContextJson(objectMapper.writeValueAsString(request));
            session.setPendingType(null);
            session.setPendingOptionsJson(null);
            session.setStatus("RESUMING");
            if (!claimResume(session, pausedStatus)) {
                throw new IllegalArgumentException("当前决策已被其他续聊请求处理，请刷新后查看最新结果");
            }
            if ("CLARIFYING".equals(pausedStatus)) {
                saveMessage(sessionId, "USER", "LOCATION", "DECLINE_LOCATION".equals(followUp.getSelectedOptionId()) ? "未提供位置，按全城搜索" : "已提供当前位置坐标");
            } else {
                saveMessage(sessionId, "USER", "RELAXATION_SELECTION", followUp.getSelectedOptionId());
            }
            log.info("[AI][session={}] state=RESUMING action=USER_FOLLOW_UP", sessionId);
            return execute(session, request, constraints, false, resumedWithRelaxation);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("续聊请求无法处理", e);
        }
    }

    private boolean claimResume(AiDecisionSession session, String pausedStatus) {
        return sessionMapper.update(session, new UpdateWrapper<AiDecisionSession>()
                .eq("id", session.getId())
                .eq("status", pausedStatus)) == 1;
    }

    private DecisionResponse execute(AiDecisionSession session, DecisionRequest request,
                                     DecisionConstraints existingConstraints, boolean extractConstraints,
                                     boolean resumedWithRelaxation) {
        DecisionResponse response = new DecisionResponse();
        response.setSessionId(session.getId());
        DecisionMetrics metrics = new DecisionMetrics();
        if (resumedWithRelaxation) metrics.setRelaxationCount(1);
        response.setMetrics(metrics);
        long start = System.currentTimeMillis();
        modelCallTracker.start(metrics);
        try {
            DecisionConstraints constraints = extractConstraints ? constraintExtractor.extract(request.getQuery()) : existingConstraints;
            reconcileRequestFacts(constraints, request);
            if ("DECLINED".equals(request.getLocationStatus())) {
                constraints.setNearby(false);
                constraints.setRadiusKm(-1D);
                removeHardConstraints(constraints, "附近", "距离", "位置");
                removeMissingInformation(constraints, "位置", "坐标", "起点");
                log.info("[AI][session={}] event=LOCATION_SLOT_REUSED status=DECLINED searchScope=CITYWIDE", session.getId());
            }
            response.setConstraints(constraints);
            session.setConstraintsJson(objectMapper.writeValueAsString(constraints));
            session.setStatus(extractConstraints ? "EXTRACTING" : "RESUMING");
            sessionMapper.updateById(session);
            if (extractConstraints) {
                recordStep(response, session.getId(), "EXTRACTING", "已提取预算、距离、菜系和场景偏好", start);
                log.info("[AI][session={}] state=EXTRACTING action=CONSTRAINTS_PARSED cuisine={} budget={} radius={} nearby={} quiet={} avoidQueue={}",
                        session.getId(), constraints.getCuisine(), constraints.getBudgetPerPerson(), constraints.getRadiusKm(),
                        constraints.getNearby(), constraints.getQuiet(), constraints.getAvoidQueue());
            }
            if (requiresLocation(constraints, request)) {
                return pauseForLocation(session, response, metrics, start);
            }
            applyNearbyDefaultRadius(constraints, session.getId());

            List<DecisionRecommendation> candidates = retrieveAndRank(request, constraints, response, metrics);
            response.setRecommendations(candidates);
            recordCompletedStep(response, session.getId(), "RETRIEVING", "从结构化数据和证据中召回候选", metrics.getRetrievingDurationMs());
            recordCompletedStep(response, session.getId(), "RERANKING", "在 " + candidates.size() + " 家候选中完成确定性重排", metrics.getRerankingDurationMs());
            if (candidates.isEmpty()) {
                return pauseForRelaxation(session, response, metrics, start);
            }

            long answerStart = System.currentTimeMillis();
            response.setAnswer(generateAnswer(session.getId(), request.getQuery(), constraints, candidates, metrics));
            recordStep(response, session.getId(), "ANSWERING", "已生成带证据的消费建议", answerStart);
            populateDurationMetrics(metrics, response.getTrace(), start);
            populateModelUsage(response, metrics);
            response.setStatus("COMPLETED");
            session.setStatus("COMPLETED");
            session.setPendingType(null);
            session.setPendingOptionsJson(null);
            session.setResultJson(objectMapper.writeValueAsString(response));
            sessionMapper.updateById(session);
            persistMetrics(session.getId(), metrics);
            saveMessage(session.getId(), "ASSISTANT", "FINAL_ANSWER", response.getAnswer());
            log.info("[AI][session={}] state=COMPLETED action=DECISION_FINISHED candidates={} modelCalls={} modelSuccess={} totalMs={}",
                    session.getId(), metrics.getFinalCandidateCount(), metrics.getModelCallCount(), metrics.getModelSuccessCount(), metrics.getTotalDurationMs());
            logDecisionOutput(session.getId(), response);
            return response;
        } catch (Exception e) {
            session.setStatus("FAILED");
            sessionMapper.updateById(session);
            throw new IllegalStateException("AI 决策执行失败: " + e.getMessage(), e);
        } finally {
            modelCallTracker.clear();
        }
    }

    private boolean requiresLocation(DecisionConstraints constraints, DecisionRequest request) {
        return (constraints.getRadiusKm() > 0 || Boolean.TRUE.equals(constraints.getNearby()))
                && (request.getLatitude() == null || request.getLongitude() == null);
    }

    private void reconcileRequestFacts(DecisionConstraints constraints, DecisionRequest request) {
        resolveDemoPlace(request, constraints);
        if (request.getLatitude() != null && request.getLongitude() != null) {
            removeMissingInformation(constraints, "位置", "坐标", "起点");
        }
        if (constraints.getOccasion().isEmpty() && isDateIntent(request.getQuery())) {
            constraints.setOccasion("约会");
            if (!constraints.getSoftPreferences().contains("根据伴侣表达识别为约会场景")) {
                constraints.getSoftPreferences().add("根据伴侣表达识别为约会场景");
            }
            removeMissingInformation(constraints, "场景", "约会");
        }
        if (request.getQuery().contains("晚上") && !EVENING_WITH_EXPLICIT_TIME.matcher(request.getQuery()).find()) {
            constraints.setArrivalTime("19:00");
            if (!constraints.getSoftPreferences().contains("“晚上”按默认 19:00 解释")) {
                constraints.getSoftPreferences().add("“晚上”按默认 19:00 解释");
            }
            removeMissingInformation(constraints, "时间", "晚上");
        }
    }

    private void removeMissingInformation(DecisionConstraints constraints, String... keywords) {
        List<String> filtered = new ArrayList<>();
        for (String item : constraints.getMissingInformation()) {
            boolean matched = false;
            for (String keyword : keywords) {
                if (item.contains(keyword)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) filtered.add(item);
        }
        constraints.setMissingInformation(filtered);
    }

    private void removeHardConstraints(DecisionConstraints constraints, String... keywords) {
        List<String> filtered = new ArrayList<>();
        for (String item : constraints.getHardConstraints()) {
            boolean matched = false;
            for (String keyword : keywords) {
                if (item.contains(keyword)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) filtered.add(item);
        }
        constraints.setHardConstraints(filtered);
    }

    private boolean isDateIntent(String query) {
        return query.contains("约会") || query.contains("女朋友") || query.contains("男朋友") || query.contains("情侣");
    }

    private void applyNearbyDefaultRadius(DecisionConstraints constraints, Long sessionId) {
        if (Boolean.TRUE.equals(constraints.getNearby()) && constraints.getRadiusKm() <= 0) {
            constraints.setRadiusKm(3D);
            constraints.getSoftPreferences().add("“附近”按默认 3km 解释");
            log.info("[AI][session={}] state=RETRIEVING action=NEARBY_DEFAULT_RADIUS radiusKm=3.0", sessionId);
        }
    }

    private DecisionResponse pauseForLocation(AiDecisionSession session, DecisionResponse response,
                                              DecisionMetrics metrics, long startedAt) throws Exception {
        response.setStatus("CLARIFYING");
        response.setQuestion("你提到了附近或距离范围，请提供当前位置的 latitude 和 longitude 后继续搜索。");
        response.getOptions().add(new DecisionOption("PROVIDE_LOCATION", "提交当前位置坐标后继续"));
        response.getOptions().add(new DecisionOption("DECLINE_LOCATION", "不提供位置，按全城搜索"));
        response.getOptions().add(new DecisionOption("END_DECISION", "结束本次推荐"));
        recordStep(response, session.getId(), "CLARIFYING", "缺少位置坐标，等待用户补充", startedAt);
        return finishPausedDecision(session, response, metrics, "CLARIFYING", "LOCATION", startedAt);
    }

    private DecisionResponse pauseForRelaxation(AiDecisionSession session, DecisionResponse response,
                                                DecisionMetrics metrics, long startedAt) throws Exception {
        DecisionConstraints constraints = response.getConstraints();
        response.setStatus("WAITING_RELAXATION");
        response.setQuestion("当前条件下没有找到匹配的餐饮商户。你可以选择明确放宽一项条件继续，或结束本次推荐；系统不会自动修改你的限制。");
        if (constraints.getRadiusKm() > 0) {
            response.getOptions().add(new DecisionOption("EXPAND_RADIUS", "扩大搜索距离到 " + round(constraints.getRadiusKm() + 2D) + " km"));
        }
        if (constraints.getBudgetPerPerson() > 0) {
            response.getOptions().add(new DecisionOption("INCREASE_BUDGET", "将人均预算提高 50 元"));
        }
        if (!constraints.getCuisine().isEmpty()) {
            response.getOptions().add(new DecisionOption("RELAX_CUISINE", "保留其他条件，允许其他菜系"));
        }
        if (Boolean.TRUE.equals(constraints.getQuiet())) {
            response.getOptions().add(new DecisionOption("RELAX_QUIET", "保留其他条件，不再强制安静环境"));
        }
        if (Boolean.TRUE.equals(constraints.getAvoidQueue())) {
            response.getOptions().add(new DecisionOption("ALLOW_QUEUE", "保留其他条件，允许存在排队风险"));
        }
        response.getOptions().add(new DecisionOption("END_DECISION", "结束本次推荐"));
        recordStep(response, session.getId(), "WAITING_RELAXATION", "候选为空，等待用户明确选择放宽项", startedAt);
        return finishPausedDecision(session, response, metrics, "WAITING_RELAXATION", "RELAXATION", startedAt);
    }

    private DecisionResponse finishPausedDecision(AiDecisionSession session, DecisionResponse response,
                                                  DecisionMetrics metrics, String state, String pendingType,
                                                  long startedAt) throws Exception {
        populateDurationMetrics(metrics, response.getTrace(), startedAt);
        populateModelUsage(response, metrics);
        session.setStatus(state);
        session.setPendingType(pendingType);
        session.setPendingOptionsJson(objectMapper.writeValueAsString(response.getOptions()));
        session.setResultJson(objectMapper.writeValueAsString(response));
        sessionMapper.updateById(session);
        persistMetrics(session.getId(), metrics);
        saveMessage(session.getId(), "ASSISTANT", pendingType + "_QUESTION", response.getQuestion());
        log.info("[AI][session={}] state={} action=WAITING_USER options={} modelCalls={} totalMs={}",
                session.getId(), state, response.getOptions().size(), metrics.getModelCallCount(), metrics.getTotalDurationMs());
        logDecisionOutput(session.getId(), response);
        return response;
    }

    private void applyRelaxation(DecisionConstraints constraints, String optionId) {
        if ("EXPAND_RADIUS".equals(optionId) && constraints.getRadiusKm() > 0) {
            constraints.setRadiusKm(round(constraints.getRadiusKm() + 2D));
        } else if ("INCREASE_BUDGET".equals(optionId) && constraints.getBudgetPerPerson() > 0) {
            constraints.setBudgetPerPerson(constraints.getBudgetPerPerson() + 50);
        } else if ("RELAX_CUISINE".equals(optionId) && !constraints.getCuisine().isEmpty()) {
            constraints.setCuisine("");
        } else if ("RELAX_QUIET".equals(optionId) && Boolean.TRUE.equals(constraints.getQuiet())) {
            constraints.setQuiet(false);
        } else if ("ALLOW_QUEUE".equals(optionId) && Boolean.TRUE.equals(constraints.getAvoidQueue())) {
            constraints.setAvoidQueue(false);
        } else {
            throw new IllegalArgumentException("selectedOptionId 无效或不适用于当前约束");
        }
    }

    private boolean isEndRequest(DecisionFollowUpRequest followUp) {
        if (followUp == null) return false;
        if ("END_DECISION".equals(followUp.getSelectedOptionId())) return true;
        String message = followUp.getMessage() == null ? "" : followUp.getMessage().trim();
        return message.contains("算了") || message.contains("不找了") || message.contains("结束") || message.contains("没你事了");
    }

    private String compact(String value) {
        if (value == null) return "";
        String result = value.replaceAll("[\\r\\n\\t]+", " ");
        return result.length() > 800 ? result.substring(0, 800) + "..." : result;
    }

    private DecisionResponse cancelPausedDecision(AiDecisionSession session, DecisionFollowUpRequest followUp) throws Exception {
        String pausedStatus = session.getStatus();
        session.setStatus("CANCELLED");
        session.setPendingType(null);
        session.setPendingOptionsJson(null);
        if (!claimResume(session, pausedStatus)) {
            throw new IllegalArgumentException("当前决策已被其他续聊请求处理，请刷新后查看最新结果");
        }
        DecisionResponse response = new DecisionResponse();
        response.setSessionId(session.getId());
        response.setStatus("CANCELLED");
        response.setAnswer("已结束本次推荐。需要新的消费建议时，请发起新的请求。");
        session.setResultJson(objectMapper.writeValueAsString(response));
        sessionMapper.updateById(session);
        saveMessage(session.getId(), "USER", "SESSION_ENDED", followUp.getMessage() == null ? "END_DECISION" : followUp.getMessage());
        saveMessage(session.getId(), "ASSISTANT", "SESSION_ENDED", response.getAnswer());
        log.info("[AI][session={}] state=CANCELLED action=USER_ENDED_SESSION", session.getId());
        logDecisionOutput(session.getId(), response);
        return response;
    }

    private void resolveDemoPlace(DecisionRequest request, DecisionConstraints constraints) {
        if (request.getLatitude() != null || request.getLongitude() != null) return;
        for (Map.Entry<String, double[]> entry : DEMO_PLACE_COORDINATES.entrySet()) {
            if (request.getQuery().contains(entry.getKey())) {
                request.setLatitude(entry.getValue()[0]);
                request.setLongitude(entry.getValue()[1]);
                constraints.getSoftPreferences().add("演示地点“" + entry.getKey() + "”已转换为坐标");
                removeMissingInformation(constraints, "位置", "坐标", "起点");
                return;
            }
        }
    }

    private static Map<String, double[]> demoPlaceCoordinates() {
        Map<String, double[]> places = new LinkedHashMap<>();
        places.put("福州鼓楼", new double[]{26.0871D, 119.2998D});
        places.put("上街大学城", new double[]{26.0745D, 119.1978D});
        places.put("闽侯", new double[]{26.0745D, 119.1978D});
        places.put("鼓楼", new double[]{26.0871D, 119.2998D});
        places.put("西湖文化广场", new double[]{30.3127D, 120.1467D});
        places.put("运河上街", new double[]{30.3186D, 120.1486D});
        places.put("武林广场", new double[]{30.3252D, 120.1505D});
        return places;
    }

    private void logDecisionOutput(Long sessionId, DecisionResponse response) {
        List<String> candidates = new ArrayList<String>();
        for (DecisionRecommendation item : response.getRecommendations()) {
            candidates.add(item.getShopId() + ":" + item.getShopName());
        }
        String output = response.getAnswer() == null ? response.getQuestion() : response.getAnswer();
        log.info("[AI][session={}] event=OUTPUT status={} candidates={} answer={}", sessionId,
                response.getStatus(), candidates, compact(output));
    }

    private void saveMessage(Long sessionId, String role, String messageType, String content) {
        AiDecisionMessage message = new AiDecisionMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setMessageType(messageType);
        message.setContent(content);
        messageMapper.insert(message);
    }

    private void populateModelUsage(DecisionResponse response, DecisionMetrics metrics) {
        response.setUsedModel(metrics.getModelSuccessCount() > 0);
        if (metrics.getModelFailureCount() > 0) {
            response.setDegradedReason("模型约束提取未成功，本次已使用本地规则继续处理。");
        } else if (!aiProperties.isConfigured()) {
            response.setDegradedReason("未配置模型服务，本次已使用本地规则处理。");
        }
    }

    public DecisionResponse getDecision(Long sessionId) {
        AiDecisionSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("决策记录不存在");
        }
        ensureSessionOwner(session);
        try {
            DecisionResponse response = session.getResultJson() == null || session.getResultJson().isEmpty()
                    ? new DecisionResponse() : objectMapper.readValue(session.getResultJson(), DecisionResponse.class);
            response.setSessionId(sessionId);
            response.setStatus(session.getStatus());
            List<AiDecisionStep> steps = stepMapper.selectList(new QueryWrapper<AiDecisionStep>()
                    .eq("session_id", sessionId).orderByAsc("id"));
            List<DecisionTraceItem> trace = new ArrayList<>();
            for (AiDecisionStep step : steps) {
                trace.add(new DecisionTraceItem(step.getState(), step.getSummary(), step.getDurationMs()));
            }
            response.setTrace(trace);
            return response;
        } catch (Exception e) {
            throw new IllegalStateException("决策记录无法解析", e);
        }
    }

    private List<DecisionRecommendation> retrieveAndRank(DecisionRequest request, DecisionConstraints constraints,
                                                          DecisionResponse response, DecisionMetrics metrics) {
        long retrievingStart = System.currentTimeMillis();
        List<Shop> shops = shopMapper.selectList(null);
        metrics.setInitialCandidateCount(shops.size());
        List<AiShopProfile> profiles = profileMapper.selectList(null);
        Map<Long, AiShopProfile> profileByShopId = profiles.stream().collect(Collectors.toMap(
                AiShopProfile::getShopId, item -> item, (left, right) -> left));

        List<Shop> hardMatched = shops.stream().filter(shop -> matchesHardConstraints(shop, profileByShopId.get(shop.getId()), request, constraints))
                .collect(Collectors.toList());
        metrics.setHardMatchedCandidateCount(hardMatched.size());
        log.info("[AI][session={}] state=RETRIEVING action=HARD_FILTER initial={} hardMatched={}",
                response.getSessionId(), shops.size(), hardMatched.size());

        List<Long> shopIds = hardMatched.stream().map(Shop::getId).collect(Collectors.toList());
        Map<Long, List<AiReviewDocument>> reviewsByShopId = new HashMap<>();
        if (!shopIds.isEmpty()) {
            List<AiReviewDocument> documents = reviewMapper.selectList(new QueryWrapper<AiReviewDocument>()
                    .in("shop_id", shopIds));
            reviewsByShopId = documents.stream().collect(Collectors.groupingBy(AiReviewDocument::getShopId));
        }
        metrics.setRetrievingDurationMs(System.currentTimeMillis() - retrievingStart);

        long rerankingStart = System.currentTimeMillis();
        List<DecisionRecommendation> recommendations = new ArrayList<>();
        for (Shop shop : hardMatched) {
            DecisionRecommendation item = toRecommendation(shop, profileByShopId.get(shop.getId()),
                    reviewsByShopId.get(shop.getId()), request, constraints);
            recommendations.add(item);
        }
        recommendations.sort(Comparator.comparing(DecisionRecommendation::getScore).reversed());
        int maxCandidates = request.getMaxCandidates() == null ? 3 : Math.max(1, Math.min(request.getMaxCandidates(), 5));
        List<DecisionRecommendation> result = recommendations.size() > maxCandidates
                ? new ArrayList<>(recommendations.subList(0, maxCandidates)) : recommendations;
        int evidenceCovered = 0;
        for (DecisionRecommendation item : result) {
            if (!item.getEvidence().isEmpty()) evidenceCovered++;
        }
        metrics.setFinalCandidateCount(result.size());
        metrics.setEvidenceCoveredCandidateCount(evidenceCovered);
        metrics.setEvidenceCoverageRate(result.isEmpty() ? 0D : round((double) evidenceCovered / result.size()));
        metrics.setRerankingDurationMs(System.currentTimeMillis() - rerankingStart);
        return result;
    }

    private boolean matchesHardConstraints(Shop shop, AiShopProfile profile, DecisionRequest request, DecisionConstraints constraints) {
        if (constraints.getBudgetPerPerson() > 0 && (shop.getAvgPrice() == null || shop.getAvgPrice() > constraints.getBudgetPerPerson())) return false;
        if (!constraints.getCuisine().isEmpty() && (profile == null || !contains(profile.getCuisine(), constraints.getCuisine()))) return false;
        if (constraints.getRadiusKm() > 0 && request.getLatitude() != null && request.getLongitude() != null
                && distanceKm(request.getLatitude(), request.getLongitude(), shop.getY(), shop.getX()) > constraints.getRadiusKm()) return false;
        return isOpenAt(shop.getOpenHours(), constraints.getArrivalTime());
    }

    public boolean matchesFollowUpConstraints(Shop shop, AiShopProfile profile, DecisionRequest request,
                                              DecisionConstraints constraints) {
        return request != null && constraints != null && matchesHardConstraints(shop, profile, request, constraints);
    }

    private DecisionRecommendation toRecommendation(Shop shop, AiShopProfile profile, List<AiReviewDocument> documents,
                                                     DecisionRequest request, DecisionConstraints constraints) {
        DecisionRecommendation item = new DecisionRecommendation();
        item.setShopId(shop.getId());
        item.setShopName(shop.getName());
        item.setAvgPrice(shop.getAvgPrice());
        item.setAddress(shop.getAddress());
        item.setOpenHours(shop.getOpenHours());
        double score = shop.getScore() == null ? 0 : shop.getScore() / 10.0D / 5.0D * 20D;
        if (constraints.getBudgetPerPerson() > 0 && shop.getAvgPrice() != null) {
            score += 20D * (1D - ((double) shop.getAvgPrice() / constraints.getBudgetPerPerson()) * 0.3D);
            item.getMatchedReasons().add("人均 " + shop.getAvgPrice() + " 元，符合预算");
        }
        if (profile != null && !constraints.getCuisine().isEmpty()) {
            score += 20D;
            item.getMatchedReasons().add("菜系：" + profile.getCuisine());
        }
        if (profile != null && !constraints.getOccasion().isEmpty() && contains(profile.getSceneTags(), constraints.getOccasion())) {
            score += 12D;
            item.getMatchedReasons().add("场景标签包含" + constraints.getOccasion());
        }
        if (request.getLatitude() != null && request.getLongitude() != null) {
            double distance = distanceKm(request.getLatitude(), request.getLongitude(), shop.getY(), shop.getX());
            item.setDistanceKm(round(distance));
            if (constraints.getRadiusKm() > 0) score += Math.max(0D, 20D * (1D - distance / constraints.getRadiusKm()));
            item.getMatchedReasons().add("距离约 " + round(distance) + " km");
        }
        if (profile != null && Boolean.TRUE.equals(constraints.getQuiet()) && contains(profile.getAmbienceTags(), "安静")) {
            score += 12D;
            item.getMatchedReasons().add("环境标签包含安静");
        }
        if (profile != null && Boolean.TRUE.equals(constraints.getAvoidQueue()) && "LOW".equalsIgnoreCase(profile.getQueueLevel())) {
            score += 8D;
            item.getMatchedReasons().add("排队风险低");
        }
        if (documents != null) {
            for (AiReviewDocument document : documents) {
                if (item.getEvidence().size() >= 2) break;
                item.getEvidence().add("[" + document.getSourceType() + "] " + document.getContent());
                score += 3D;
            }
        }
        item.setScore(round(Math.min(100D, score)));
        return item;
    }

    private String generateAnswer(Long sessionId, String query, DecisionConstraints constraints, List<DecisionRecommendation> items,
                                  DecisionMetrics metrics) {
        if (items.isEmpty()) {
            return "当前数据中没有找到满足硬约束的商户。可以适当放宽预算、距离或菜系后再试。";
        }
        String narrative = "该选择在当前硬约束下的综合匹配度最高。";
        if (!aiProperties.isConfigured() || !Boolean.TRUE.equals(aiProperties.getNarrativeEnabled())) {
            return buildFactAnswer(constraints, items, narrative);
        }
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(message("system", "你是消费决策助手。仅输出一到两句泛化的取舍建议，不得出现店名、数字、价格、距离、地址、营业时间、评分、证据原文或未提供的事实。"));
            messages.add(message("user", "用户需求：" + query + "\n已满足的偏好：" + objectMapper.writeValueAsString(items.get(0).getMatchedReasons())));
            String generated = aiClient.chatText(messages, "NARRATIVE_GENERATION").trim();
            if (isSafeNarrative(generated, items)) {
                narrative = generated;
                log.info("[AI][session={}] state=ANSWERING action=MODEL_NARRATIVE_ACCEPTED", sessionId);
            } else {
                metrics.setNarrativeRejected(true);
                log.warn("[AI][session={}] state=ANSWERING action=MODEL_NARRATIVE_REJECTED reason=FACT_GUARD", sessionId);
            }
        } catch (Exception ignored) {
            // 模型调用失败时继续使用安全的后端事实答案。
            log.warn("[AI][session={}] state=ANSWERING action=MODEL_NARRATIVE_FALLBACK", sessionId);
        }
        return buildFactAnswer(constraints, items, narrative);
    }

    private String buildFactAnswer(DecisionConstraints constraints, List<DecisionRecommendation> items, String narrative) {
        DecisionRecommendation first = items.get(0);
        StringBuilder answer = new StringBuilder("首选 ").append(first.getShopName()).append("。\n\n匹配依据：");
        for (String reason : first.getMatchedReasons()) answer.append("\n- ").append(reason);
        if (items.size() > 1) answer.append("\n\n备选：").append(items.get(1).getShopName());
        if (!first.getEvidence().isEmpty()) {
            answer.append("\n\n证据：");
            for (String evidence : first.getEvidence()) answer.append("\n- ").append(evidence);
        }
        answer.append("\n\n建议：").append(narrative);
        if (constraints.getMissingInformation() != null && !constraints.getMissingInformation().isEmpty()) {
            answer.append("\n\n注意：").append(String.join("；", constraints.getMissingInformation()));
        }
        if (containsDemoPlaceResolution(constraints)) {
            answer.append("\n\n说明：地点名称使用演示坐标解析，距离仅用于演示。");
        }
        return answer.toString();
    }

    private boolean containsDemoPlaceResolution(DecisionConstraints constraints) {
        for (String preference : constraints.getSoftPreferences()) {
            if (preference.startsWith("演示地点“")) return true;
        }
        return false;
    }

    private boolean isSafeNarrative(String narrative, List<DecisionRecommendation> items) {
        if (narrative.isEmpty() || narrative.length() > 240 || narrative.matches(".*\\d.*")) return false;
        String[] prohibitedTerms = {"人均", "价格", "距离", "公里", "地址", "营业", "评分", "证据", "元"};
        for (String term : prohibitedTerms) if (narrative.contains(term)) return false;
        for (DecisionRecommendation item : items) if (narrative.contains(item.getShopName())) return false;
        return true;
    }

    private void populateDurationMetrics(DecisionMetrics metrics, List<DecisionTraceItem> trace, long startedAt) {
        metrics.setTotalDurationMs(System.currentTimeMillis() - startedAt);
        for (DecisionTraceItem item : trace) {
            if ("EXTRACTING".equals(item.getState())) metrics.setExtractingDurationMs(item.getDurationMs());
            if ("ANSWERING".equals(item.getState())) metrics.setAnsweringDurationMs(item.getDurationMs());
        }
    }

    private void persistMetrics(Long sessionId, DecisionMetrics metrics) {
        AiDecisionMetric metric = new AiDecisionMetric();
        metric.setSessionId(sessionId);
        Integer previousAttempts = metricMapper.selectCount(new QueryWrapper<AiDecisionMetric>().eq("session_id", sessionId));
        metric.setAttemptNo(previousAttempts + 1);
        metric.setTotalDurationMs(metrics.getTotalDurationMs());
        metric.setExtractingDurationMs(metrics.getExtractingDurationMs());
        metric.setRetrievingDurationMs(metrics.getRetrievingDurationMs());
        metric.setRerankingDurationMs(metrics.getRerankingDurationMs());
        metric.setAnsweringDurationMs(metrics.getAnsweringDurationMs());
        metric.setModelCallCount(metrics.getModelCallCount());
        metric.setModelSuccessCount(metrics.getModelSuccessCount());
        metric.setModelFailureCount(metrics.getModelFailureCount());
        metric.setInitialCandidateCount(metrics.getInitialCandidateCount());
        metric.setHardMatchedCandidateCount(metrics.getHardMatchedCandidateCount());
        metric.setFinalCandidateCount(metrics.getFinalCandidateCount());
        metric.setRelaxationCount(metrics.getRelaxationCount());
        metric.setEvidenceCoveredCandidateCount(metrics.getEvidenceCoveredCandidateCount());
        metric.setEvidenceCoverageRate(metrics.getEvidenceCoverageRate());
        metric.setFactualConsistent(metrics.getFactualConsistent());
        metric.setNarrativeRejected(metrics.getNarrativeRejected());
        metricMapper.insert(metric);
    }

    private void recordStep(DecisionResponse response, Long sessionId, String state, String summary, long startedAt) {
        long duration = Math.max(0L, System.currentTimeMillis() - startedAt);
        response.getTrace().add(new DecisionTraceItem(state, summary, duration));
        AiDecisionStep step = new AiDecisionStep();
        step.setSessionId(sessionId);
        step.setState(state);
        step.setSummary(summary);
        step.setDurationMs(duration);
        stepMapper.insert(step);
    }

    private void recordCompletedStep(DecisionResponse response, Long sessionId, String state, String summary, Long durationMs) {
        long duration = durationMs == null ? 0L : durationMs;
        response.getTrace().add(new DecisionTraceItem(state, summary, duration));
        AiDecisionStep step = new AiDecisionStep();
        step.setSessionId(sessionId);
        step.setState(state);
        step.setSummary(summary);
        step.setDurationMs(duration);
        stepMapper.insert(step);
    }

    private boolean contains(String source, String expected) {
        return source != null && source.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private void ensureSessionOwner(AiDecisionSession session) {
        if (session.getUserId() == null) return;
        if (UserHolder.getUser() == null || !session.getUserId().equals(UserHolder.getUser().getId())) {
            throw new SecurityException("无权访问其他用户的决策会话");
        }
    }

    private boolean isOpenAt(String openHours, String arrivalTime) {
        if (arrivalTime == null || arrivalTime.isEmpty() || openHours == null || openHours.trim().isEmpty()) return true;
        try {
            int target = minuteOfDay(arrivalTime);
            String[] ranges = openHours.replace(" ", "").split(",");
            for (String range : ranges) {
                String[] parts = range.split("-");
                if (parts.length != 2) continue;
                int begin = minuteOfDay(parts[0]);
                int end = minuteOfDay(parts[1]);
                if (end < begin ? target >= begin || target <= end : target >= begin && target <= end) return true;
            }
            return false;
        } catch (Exception ignored) {
            return true;
        }
    }

    private int minuteOfDay(String value) {
        String[] parts = value.trim().split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private double distanceKm(double latitude, double longitude, double shopLatitude, double shopLongitude) {
        double earthRadius = 6371D;
        double latDistance = Math.toRadians(shopLatitude - latitude);
        double lonDistance = Math.toRadians(shopLongitude - longitude);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(shopLatitude))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
