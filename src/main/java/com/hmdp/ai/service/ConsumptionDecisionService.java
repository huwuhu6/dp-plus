package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.client.SpringAiTextClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
import com.hmdp.ai.dto.DecisionMetrics;
import com.hmdp.ai.dto.DecisionOption;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.DecisionTraceItem;
import com.hmdp.ai.dto.RelaxationInfo;
import com.hmdp.ai.dto.SemanticRecallResult;
import com.hmdp.ai.entity.AiDecisionSession;
import com.hmdp.ai.entity.AiDecisionMetric;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.mapper.AiDecisionSessionMapper;
import com.hmdp.ai.mapper.AiDecisionStepMapper;
import com.hmdp.ai.mapper.AiDecisionMetricMapper;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.mapper.AiDecisionMessageMapper;
import com.hmdp.ai.runtime.ConversationEventStatus;
import com.hmdp.ai.runtime.ConversationEventType;
import com.hmdp.ai.runtime.DecisionCommand;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Resource;
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
    @Resource private ConstraintExtractor constraintExtractor;
    @Resource private AiModelCallTracker modelCallTracker;
    @Resource private OpenAiCompatibleClient aiClient;
    @Resource private SpringAiTextClient springAiTextClient;
    @Resource private AiProperties aiProperties;
    @Resource private ObjectMapper objectMapper;
    @Resource private ShopMapper shopMapper;
    @Resource private AiShopProfileMapper profileMapper;
    @Resource private AiReviewDocumentMapper reviewMapper;
    @Resource private AiDecisionSessionMapper sessionMapper;
    // Retained only to keep injected test fixtures and old extensions binary-compatible.
    // Runtime writes are now emitted through tbl_ai_conversation_event.
    @SuppressWarnings("unused") @Resource private AiDecisionStepMapper stepMapper;
    @Resource private AiDecisionMetricMapper metricMapper;
    @Resource private AiConversationEventMapper conversationEventMapper;
    @SuppressWarnings("unused") @Resource private AiDecisionMessageMapper messageMapper;
    @Resource private ConversationEventService conversationEventService;
    @Resource private ResultEvaluationService resultEvaluationService;
    @Autowired(required = false) private SemanticShopRetriever semanticShopRetriever;
    @Value("${ai.retrieval.semantic-weight:18}") private double semanticWeight;
    // Spring injects the domain component in production; the default keeps isolated
    // unit fixtures compatible without changing their construction pattern.
    @Resource private DecisionTransitionService transitionService = new DecisionTransitionService();

    public DecisionResponse decide(DecisionRequest request) {
        return decide(request, null);
    }

    /**
     * Starts a new decision from a chat-level merged snapshot. Constraint extraction has
     * already happened at the gateway, so the decision session receives a deterministic input.
     */
    public DecisionResponse decide(DecisionRequest request, DecisionConstraints mergedConstraints) {
        return decide(request, mergedConstraints, null, null);
    }

    /** The recommendation task remains a business lifecycle, linked to but not replacing Runtime IDs. */
    public DecisionResponse decide(DecisionRequest request, DecisionConstraints mergedConstraints, String chatId, String traceId) {
        if (request == null || request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        log.info("[AI][decision] event=REQUEST_RECEIVED query={} latitude={} longitude={} maxCandidates={}",
                compact(request.getQuery()), request.getLatitude(), request.getLongitude(), request.getMaxCandidates());
        AiDecisionSession session = new AiDecisionSession();
        session.setUserId(UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
        session.setChatId(chatId);
        session.setTraceId(traceId);
        session.setQueryText(request.getQuery().trim());
        transitionService.transition(session, DecisionCommand.START_DECISION);
        try {
            session.setRequestContextJson(objectMapper.writeValueAsString(request));
        } catch (Exception e) {
            throw new IllegalStateException("请求上下文无法保存", e);
        }
        sessionMapper.insert(session);
        saveMessage(session.getId(), "USER", "INITIAL_QUERY", request.getQuery());
        log.info("[AI][session={}] state=CREATED action=SESSION_CREATED", session.getId());
        return execute(session, request, mergedConstraints, mergedConstraints == null, false);
    }

    public DecisionResponse continueDecision(Long sessionId, DecisionFollowUpRequest followUp) {
        AiDecisionSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new IllegalArgumentException("决策记录不存在");
        ensureSessionOwner(session);
        log.info("[AI][session={}] state={} action=FOLLOW_UP_RECEIVED selectedOptionId={} hasLatitude={} hasLongitude={} message={}",
                sessionId, session.getStatus(), followUp == null ? null : followUp.getSelectedOptionId(),
                followUp != null && followUp.getLatitude() != null, followUp != null && followUp.getLongitude() != null,
                followUp == null ? "" : compact(followUp.getMessage()));
        try {
            DecisionCommand command = resolveFollowUpCommand(followUp);
            String pausedStatus = session.getStatus();
            validateSelectedOption(session, followUp, command);
            if (command == DecisionCommand.END_DECISION) return cancelPausedDecision(session, followUp, pausedStatus);
            if (command == DecisionCommand.SWITCH_CITY) {
                transitionService.resolve(pausedStatus, command);
                return switchCityResponse(session);
            }
            // Validate state/command before mutating request constraints or durable Working Memory upstream.
            transitionService.resolve(pausedStatus, command);
            DecisionRequest request = objectMapper.readValue(session.getRequestContextJson(), DecisionRequest.class);
            DecisionConstraints constraints = objectMapper.readValue(session.getConstraintsJson(), DecisionConstraints.class);
            boolean resumedWithRelaxation = "WAITING_RELAXATION".equals(pausedStatus);
            if ("CLARIFYING".equals(pausedStatus)) {
                if (command == DecisionCommand.DECLINE_LOCATION) {
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
                    request.setProvince(followUp.getProvince());
                    request.setCity(followUp.getCity());
                    request.setDistrict(followUp.getDistrict());
                    request.setLocationStatus("AVAILABLE");
                    log.info("[AI][session={}] state=CLARIFYING action=LOCATION_ACCEPTED latitude={} longitude={}",
                            sessionId, request.getLatitude(), request.getLongitude());
                }
            } else if ("WAITING_RELAXATION".equals(pausedStatus)) {
                applyRelaxation(constraints, command);
                log.info("[AI][session={}] state=WAITING_RELAXATION action=RELAXATION_SELECTED option={}",
                        sessionId, followUp.getSelectedOptionId());
            } else {
                throw new IllegalArgumentException("当前目标城市暂无入库商户，请切换城市后重新发起搜索");
            }
            session.setRequestContextJson(objectMapper.writeValueAsString(request));
            session.setPendingType(null);
            session.setPendingOptionsJson(null);
            transitionService.transition(session, command);
            transitionService.validatePendingState(session.getStatus(), session.getPendingType(), Collections.emptyList(), null);
            if (!claimResume(session, pausedStatus)) {
                throw new IllegalArgumentException("当前决策已被其他续聊请求处理，请刷新后查看最新结果");
            }
            if ("CLARIFYING".equals(pausedStatus)) {
                String message = command == DecisionCommand.DECLINE_LOCATION
                        ? "未提供位置，按全城搜索"
                        : (hasText(followUp.getMessage()) ? followUp.getMessage() : "已提供当前位置坐标");
                saveMessage(sessionId, "USER", "LOCATION", message);
            } else {
                saveMessage(sessionId, "USER", "RELAXATION_SELECTION", command.name());
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
            if (extractConstraints) {
                transitionService.transition(session, DecisionCommand.EXTRACT_CONSTRAINTS);
            } else {
                transitionService.transition(session, DecisionCommand.EXECUTE);
            }
            sessionMapper.updateById(session);
            if (extractConstraints) {
                recordStep(response, session.getId(), "EXTRACTING", "已提取预算、距离、菜系和场景偏好", start);
                log.info("[AI][session={}] state=EXTRACTING action=CONSTRAINTS_PARSED cuisine={} budget={} radius={} nearby={} quiet={} avoidQueue={}",
                        session.getId(), constraints.getCuisine(), constraints.getBudgetPerPerson(), constraints.getRadiusKm(),
                        constraints.getNearby(), constraints.getQuiet(), constraints.getAvoidQueue());
            }
            if (requiresLocation(request)) {
                return pauseForLocation(session, response, metrics, start, request);
            }
            applyNearbyDefaultRadius(constraints, session.getId());
            // Keep the durable snapshot aligned with the constraints behind rendered options.
            session.setConstraintsJson(objectMapper.writeValueAsString(constraints));
            sessionMapper.updateById(session);

            List<DecisionRecommendation> candidates = retrieveAndRank(request, constraints, response, metrics);
            RelaxationInfo relaxation = resultEvaluationService.evaluateStrictResult(request, constraints, candidates.size());
            response.setRelaxation(relaxation);
            metrics.setStrictCandidateCount(candidates.size());
            metrics.setResultEvaluationOutcome(relaxation.getOutcome());
            if (resultEvaluationService.applySafeAutomaticRelaxation(request, constraints, relaxation)) {
                transitionService.resolve(session.getStatus(), DecisionCommand.AUTO_RELAXATION);
                long strictRetrievingMs = durationOrZero(metrics.getRetrievingDurationMs());
                long strictRerankingMs = durationOrZero(metrics.getRerankingDurationMs());
                long strictSemanticRetrievingMs = durationOrZero(metrics.getSemanticRetrievingDurationMs());
                long retryStartedAt = System.currentTimeMillis();
                log.info("[AI][session={}] state=RESULT_EVALUATING action=AUTO_RELAXATION_APPLIED changes={} preservedHardConstraints={}",
                        session.getId(), relaxation.getAppliedChanges(), relaxation.getPreservedHardConstraints());
                candidates = retrieveAndRank(request, constraints, response, metrics);
                metrics.setRetrievingDurationMs(strictRetrievingMs + durationOrZero(metrics.getRetrievingDurationMs()));
                metrics.setRerankingDurationMs(strictRerankingMs + durationOrZero(metrics.getRerankingDurationMs()));
                metrics.setSemanticRetrievingDurationMs(strictSemanticRetrievingMs + durationOrZero(metrics.getSemanticRetrievingDurationMs()));
                resultEvaluationService.recordRelaxedResult(relaxation, candidates.size());
                metrics.setRelaxationCount(metrics.getRelaxationCount() + 1);
                metrics.setAutomaticRelaxationApplied(true);
                metrics.setResultEvaluationOutcome(relaxation.getOutcome());
                response.setConstraints(constraints);
                session.setConstraintsJson(objectMapper.writeValueAsString(constraints));
                sessionMapper.updateById(session);
                recordStep(response, session.getId(), "RESULT_EVALUATING",
                        "默认附近范围未命中，已在保留硬约束下扩大至 " + round(constraints.getRadiusKm()) + " km 重搜",
                        retryStartedAt);
            }
            response.setRecommendations(candidates);
            if (Boolean.TRUE.equals(metrics.getSemanticRetrievalUsed())) {
                recordCompletedStep(response, session.getId(), "SEMANTIC_RETRIEVING", "在硬约束候选范围内完成语义证据召回", metrics.getSemanticRetrievingDurationMs());
            }
            recordCompletedStep(response, session.getId(), "RETRIEVING", "从结构化数据和证据中召回候选", metrics.getRetrievingDurationMs());
            recordCompletedStep(response, session.getId(), "RERANKING", "在 " + candidates.size() + " 家候选中完成确定性重排", metrics.getRerankingDurationMs());
            if (candidates.isEmpty()) {
                if (hasAdministrativeScope(request) && !hasRelaxableConstraints(constraints)) {
                    return pauseForNoData(session, request, response, metrics, start);
                }
                return pauseForRelaxation(session, response, metrics, start);
            }

            long answerStart = System.currentTimeMillis();
            response.setAnswer(generateAnswer(session.getId(), request.getQuery(), constraints, candidates, metrics, relaxation));
            recordStep(response, session.getId(), "ANSWERING", "已生成带证据的消费建议", answerStart);
            populateDurationMetrics(metrics, response.getTrace(), start);
            populateModelUsage(response, metrics);
            response.setStatus("COMPLETED");
            transitionService.transition(session, DecisionCommand.COMPLETE);
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
            transitionService.transition(session, DecisionCommand.FAIL);
            sessionMapper.updateById(session);
            throw new IllegalStateException("AI 决策执行失败: " + e.getMessage(), e);
        } finally {
            modelCallTracker.clear();
        }
    }

    private boolean requiresLocation(DecisionRequest request) {
        if (request.getLatitude() != null && request.getLongitude() != null) return false;
        // A user-selected city is a valid non-GPS search anchor.  Coordinates are
        // required only for nearby/radius calculation, not for a city-wide search.
        if (hasText(request.getCity())) return false;
        return !"DECLINED".equals(request.getLocationStatus());
    }

    private void reconcileRequestFacts(DecisionConstraints constraints, DecisionRequest request) {
        if (request.getLatitude() != null && request.getLongitude() != null) {
            removeMissingInformation(constraints, "位置", "坐标", "起点");
            if (Boolean.TRUE.equals(request.getUseLocationScope())) {
                constraints.setNearby(true);
                if (!constraints.getSoftPreferences().contains("已按会话位置在附近检索")) {
                    constraints.getSoftPreferences().add("已按会话位置在附近检索");
                }
            }
        }
        if (constraints.getOccasion().isEmpty() && isDateIntent(request.getQuery())) {
            constraints.setOccasion("约会");
            if (!constraints.getSoftPreferences().contains("根据伴侣表达识别为约会场景")) {
                constraints.getSoftPreferences().add("根据伴侣表达识别为约会场景");
            }
            removeMissingInformation(constraints, "场景", "约会");
        }
        if (isLightTasteIntent(request.getQuery())) {
            if (!constraints.getSoftPreferences().contains("口味清淡")) {
                constraints.getSoftPreferences().add("口味清淡");
            }
            removeMissingInformation(constraints, "口味", "饮食偏好");
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

    private boolean isLightTasteIntent(String query) {
        return query.contains("清淡") || query.contains("少油") || query.contains("不油腻") || query.contains("清爽");
    }

    private void applyNearbyDefaultRadius(DecisionConstraints constraints, Long sessionId) {
        if (Boolean.TRUE.equals(constraints.getNearby()) && constraints.getRadiusKm() <= 0) {
            constraints.setRadiusKm(3D);
            constraints.getSoftPreferences().add("“附近”按默认 3km 解释");
            log.info("[AI][session={}] state=RETRIEVING action=NEARBY_DEFAULT_RADIUS radiusKm=3.0", sessionId);
        }
    }

    private DecisionResponse pauseForLocation(AiDecisionSession session, DecisionResponse response,
                                              DecisionMetrics metrics, long startedAt,
                                              DecisionRequest request) throws Exception {
        response.setStatus("CLARIFYING");
        response.setQuestion("为了按你的实际位置推荐餐饮商户，请授权并提供当前位置的 latitude 和 longitude。"
                + "地点名称需要通过地理编码服务解析；当前未配置该服务时，请直接提供当前位置。");
        response.getOptions().add(new DecisionOption("PROVIDE_LOCATION", "提交当前位置坐标后继续"));
        response.getOptions().add(new DecisionOption("DECLINE_LOCATION", "不提供位置，按全城搜索"));
        response.getOptions().add(new DecisionOption("END_DECISION", "结束本次推荐"));
        recordStep(response, session.getId(), "CLARIFYING", "默认需要位置，等待用户授权或补充", startedAt);
        return finishPausedDecision(session, response, metrics, DecisionCommand.PROVIDE_LOCATION, "LOCATION", startedAt);
    }

    private DecisionResponse pauseForRelaxation(AiDecisionSession session, DecisionResponse response,
                                                DecisionMetrics metrics, long startedAt) throws Exception {
        DecisionConstraints constraints = response.getConstraints();
        response.setStatus("WAITING_RELAXATION");
        boolean autoRetried = response.getRelaxation() != null && Boolean.TRUE.equals(response.getRelaxation().getAutomatic());
        boolean lockedBudget = isLocked(constraints, "budgetPerPerson");
        response.setQuestion(lockedBudget
                ? "当前搜索范围内暂无人均低于 " + constraints.getBudgetPerPerson()
                + " 元的商户。已保留你的“更便宜”要求，不会提高预算；你可以扩大搜索范围或结束本次推荐。"
                : autoRetried
                ? "默认附近范围已在保留地点、菜系、预算等硬条件下自动扩大一次，仍未找到匹配商户。请明确选择一项条件放宽后继续，或结束本次推荐。"
                : "当前条件下没有找到匹配的餐饮商户。你可以选择明确放宽一项条件继续，或结束本次推荐；系统不会自动修改你的限制。");
        if (constraints.getRadiusKm() > 0) {
            response.getOptions().add(new DecisionOption("EXPAND_RADIUS", "扩大搜索距离到 " + round(constraints.getRadiusKm() + 2D) + " km"));
        }
        if (constraints.getBudgetPerPerson() > 0 && !isLocked(constraints, "budgetPerPerson")) {
            response.getOptions().add(new DecisionOption("INCREASE_BUDGET", "将人均预算上限提高到 "
                    + (constraints.getBudgetPerPerson() + 50) + " 元"));
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
        if (requiresLightTasteEvidence(constraints)) {
            response.getOptions().add(new DecisionOption("RELAX_LIGHT_TASTE", "保留其他条件，不再强制清淡口味"));
        }
        if (constraints.getHardConstraints() != null && !constraints.getHardConstraints().isEmpty()) {
            response.getOptions().add(new DecisionOption("RELAX_HARD_CONSTRAINTS", "保留地点和核心需求，移除额外硬性限制"));
        }
        response.getOptions().add(new DecisionOption("END_DECISION", "结束本次推荐"));
        recordStep(response, session.getId(), "WAITING_RELAXATION", "候选为空，等待用户明确选择放宽项", startedAt);
        return finishPausedDecision(session, response, metrics, DecisionCommand.STRICT_SEARCH_EMPTY, "RELAXATION", startedAt);
    }

    /** A named city without user-relaxable filters is a data-coverage outcome, not a relaxation task. */
    private DecisionResponse pauseForNoData(AiDecisionSession session, DecisionRequest request, DecisionResponse response,
                                            DecisionMetrics metrics, long startedAt) throws Exception {
        String scope = hasText(request.getDistrict()) ? request.getDistrict()
                : (hasText(request.getCity()) ? request.getCity() : request.getProvince());
        response.setStatus("ZERO_RESULT_NO_DATA");
        response.setQuestion(scope + "目前暂无收录餐饮商户。你可以尝试切换其他城市或周边区域后再搜索。");
        response.getOptions().add(new DecisionOption("SWITCH_CITY", "切换城市重新搜索"));
        response.getOptions().add(new DecisionOption("END_DECISION", "结束本次推荐"));
        recordStep(response, session.getId(), "ZERO_RESULT_NO_DATA", "指定地理范围内暂无入库商户，不提供虚假的放宽选项", startedAt);
        log.info("[AI][session={}] state=ZERO_RESULT_NO_DATA action=NO_DATA_SCOPE scope={} relaxable=false",
                session.getId(), scope);
        return finishPausedDecision(session, response, metrics, DecisionCommand.NO_DATA_FOUND, "NO_DATA", startedAt);
    }

    private boolean hasRelaxableConstraints(DecisionConstraints constraints) {
        return (constraints.getBudgetPerPerson() > 0 && !isLocked(constraints, "budgetPerPerson")) || constraints.getRadiusKm() > 0
                || hasText(constraints.getCuisine()) || Boolean.TRUE.equals(constraints.getQuiet())
                || Boolean.TRUE.equals(constraints.getAvoidQueue()) || requiresLightTasteEvidence(constraints)
                || (constraints.getHardConstraints() != null && !constraints.getHardConstraints().isEmpty());
    }

    private DecisionResponse finishPausedDecision(AiDecisionSession session, DecisionResponse response,
                                                  DecisionMetrics metrics, DecisionCommand command, String pendingType,
                                                  long startedAt) throws Exception {
        populateDurationMetrics(metrics, response.getTrace(), startedAt);
        populateModelUsage(response, metrics);
        transitionService.transition(session, command);
        String state = session.getStatus();
        if (!state.equals(response.getStatus())) {
            throw new IllegalStateException("响应状态与决策状态转移不一致");
        }
        session.setPendingType(pendingType);
        session.setPendingOptionsJson(objectMapper.writeValueAsString(response.getOptions()));
        transitionService.validatePendingState(state, pendingType, response.getOptions(), response.getQuestion());
        session.setResultJson(objectMapper.writeValueAsString(response));
        sessionMapper.updateById(session);
        persistMetrics(session.getId(), metrics);
        saveMessage(session.getId(), "ASSISTANT", pendingType + "_QUESTION", response.getQuestion());
        log.info("[AI][session={}] state={} action=WAITING_USER options={} modelCalls={} totalMs={}",
                session.getId(), state, response.getOptions().size(), metrics.getModelCallCount(), metrics.getTotalDurationMs());
        logDecisionOutput(session.getId(), response);
        return response;
    }

    private void applyRelaxation(DecisionConstraints constraints, DecisionCommand command) {
        if (command == DecisionCommand.EXPAND_RADIUS && constraints.getRadiusKm() > 0) {
            constraints.setRadiusKm(round(constraints.getRadiusKm() + 2D));
        } else if (command == DecisionCommand.INCREASE_BUDGET && constraints.getBudgetPerPerson() > 0
                && !isLocked(constraints, "budgetPerPerson")) {
            log.info("[AI][decision] action=BUDGET_RELAXATION previousBudget={} nextBudget={}",
                    constraints.getBudgetPerPerson(), constraints.getBudgetPerPerson() + 50);
            constraints.setBudgetPerPerson(constraints.getBudgetPerPerson() + 50);
        } else if (command == DecisionCommand.RELAX_CUISINE && !constraints.getCuisine().isEmpty()) {
            constraints.setCuisine("");
        } else if (command == DecisionCommand.RELAX_QUIET && Boolean.TRUE.equals(constraints.getQuiet())) {
            constraints.setQuiet(false);
        } else if (command == DecisionCommand.ALLOW_QUEUE && Boolean.TRUE.equals(constraints.getAvoidQueue())) {
            constraints.setAvoidQueue(false);
        } else if (command == DecisionCommand.RELAX_LIGHT_TASTE && requiresLightTasteEvidence(constraints)) {
            constraints.getSoftPreferences().remove("口味清淡");
        } else if (command == DecisionCommand.RELAX_HARD_CONSTRAINTS
                && constraints.getHardConstraints() != null && !constraints.getHardConstraints().isEmpty()) {
            constraints.setHardConstraints(new ArrayList<>());
        } else {
            throw new IllegalArgumentException("Decision Command 无效或不适用于当前约束: " + command);
        }
    }

    private boolean isLocked(DecisionConstraints constraints, String field) {
        return constraints.getLockedConstraints() != null && constraints.getLockedConstraints().contains(field);
    }

    private DecisionCommand resolveFollowUpCommand(DecisionFollowUpRequest followUp) {
        if (followUp != null && hasText(followUp.getSelectedOptionId())) {
            return transitionService.commandForOption(followUp.getSelectedOptionId());
        }
        if (followUp != null && followUp.getLatitude() != null && followUp.getLongitude() != null) {
            return DecisionCommand.PROVIDE_LOCATION;
        }
        String message = followUp == null || followUp.getMessage() == null ? "" : followUp.getMessage().trim();
        if (message.contains("算了") || message.contains("不找了") || message.contains("结束") || message.contains("没你事了")) {
            return DecisionCommand.END_DECISION;
        }
        throw new IllegalArgumentException("当前决策需要 selectedOptionId 指定下一步操作");
    }

    private String compact(String value) {
        if (value == null) return "";
        String result = value.replaceAll("[\\r\\n\\t]+", " ");
        return result.length() > 800 ? result.substring(0, 800) + "..." : result;
    }

    private DecisionResponse cancelPausedDecision(AiDecisionSession session, DecisionFollowUpRequest followUp,
                                                   String pausedStatus) throws Exception {
        transitionService.transition(session, DecisionCommand.END_DECISION);
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

    private DecisionResponse switchCityResponse(AiDecisionSession session) {
        DecisionResponse response = new DecisionResponse();
        response.setSessionId(session.getId());
        response.setStatus(session.getStatus());
        response.setAnswer("请直接告诉我想切换到哪个城市，例如“帮我看看福州有什么好吃的”。");
        return response;
    }

    /** Used by chat adapters before they mutate location-related Working Memory. */
    public DecisionCommand validateSelectedOption(Long sessionId, String optionId) {
        AiDecisionSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new IllegalArgumentException("决策记录不存在");
        ensureSessionOwner(session);
        return transitionService.validateSelectedOption(session.getStatus(), optionId, pendingOptions(session));
    }

    private void validateSelectedOption(AiDecisionSession session, DecisionFollowUpRequest followUp,
                                        DecisionCommand command) throws Exception {
        if (followUp == null || !hasText(followUp.getSelectedOptionId())) return;
        DecisionCommand selectedCommand = transitionService.validateSelectedOption(
                session.getStatus(), followUp.getSelectedOptionId(), pendingOptions(session));
        if (selectedCommand != command) {
            throw new IllegalArgumentException("selectedOptionId 与决策命令不一致");
        }
    }

    private List<String> pendingOptions(AiDecisionSession session) {
        if (!hasText(session.getPendingOptionsJson())) return Collections.emptyList();
        try {
            JsonNode options = objectMapper.readTree(session.getPendingOptionsJson());
            if (options == null || !options.isArray()) return Collections.emptyList();
            List<String> ids = new ArrayList<String>();
            for (JsonNode option : options) {
                JsonNode id = option.get("id");
                if (id != null && hasText(id.asText())) ids.add(id.asText());
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("决策待选项无法解析", e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private long durationOrZero(Long duration) {
        return duration == null ? 0L : duration;
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
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("decisionSessionId", sessionId);
        result.put("role", role);
        result.put("messageType", messageType);
        result.put("content", content);
        if (conversationEventService != null) {
            conversationEventService.record("USER".equals(role) ? ConversationEventType.USER_INPUT : ConversationEventType.ASSISTANT_OUTPUT,
                    ConversationEventStatus.SUCCESS, null, null, result, null);
        }
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
            List<DecisionTraceItem> trace = new ArrayList<>();
            if (hasText(session.getChatId()) && hasText(session.getTraceId())) {
                List<AiConversationEvent> events = conversationEventMapper.selectList(new QueryWrapper<AiConversationEvent>()
                        .eq("chat_id", session.getChatId()).eq("trace_id", session.getTraceId()).orderByAsc("sequence_no"));
                for (AiConversationEvent event : events) {
                    if (!hasText(event.getEventResult())) continue;
                    Map<String, Object> eventResult = objectMapper.readValue(event.getEventResult(), Map.class);
                    if (!sessionId.equals(asLong(eventResult.get("decisionSessionId"))) || eventResult.get("state") == null) continue;
                    Number duration = (Number) eventResult.get("durationMs");
                    trace.add(new DecisionTraceItem(String.valueOf(eventResult.get("state")),
                            String.valueOf(eventResult.get("summary")), duration == null ? 0L : duration.longValue()));
                }
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
        QueryWrapper<Shop> shopQuery = new QueryWrapper<Shop>();
        if (hasText(request.getProvince())) shopQuery.eq("province", request.getProvince());
        if (hasText(request.getCity())) shopQuery.in("city", administrativeNameAliases(request.getCity()));
        if (hasText(request.getDistrict())) shopQuery.eq("district", request.getDistrict());
        if (request.getExcludeShopIds() != null && !request.getExcludeShopIds().isEmpty()) {
            shopQuery.notIn("id", request.getExcludeShopIds());
        }
        List<Shop> shops = shopMapper.selectList(shopQuery);
        if (hasAdministrativeScope(request)) {
            log.info("[AI][session={}] state=RETRIEVING action=ADMIN_SCOPE_FILTER province={} city={} district={} candidates={}",
                    response.getSessionId(), request.getProvince(), request.getCity(), request.getDistrict(), shops.size());
        }
        metrics.setInitialCandidateCount(shops.size());
        List<AiShopProfile> profiles = profileMapper.selectList(null);
        Map<Long, AiShopProfile> profileByShopId = profiles.stream().collect(Collectors.toMap(
                AiShopProfile::getShopId, item -> item, (left, right) -> left));

        List<Shop> hardMatched = shops.stream().filter(shop -> matchesHardConstraints(shop, profileByShopId.get(shop.getId()), request, constraints))
                .collect(Collectors.toList());
        if (requiresLightTasteEvidence(constraints)) {
            List<Long> shopIds = hardMatched.stream().map(Shop::getId).collect(Collectors.toList());
            Map<Long, List<AiReviewDocument>> preferenceDocuments = loadReviewsByShopId(shopIds);
            hardMatched = hardMatched.stream().filter(shop -> hasLightTasteEvidence(preferenceDocuments.get(shop.getId())))
                    .collect(Collectors.toList());
            log.info("[AI][session={}] state=RETRIEVING action=EVIDENCE_PREFERENCE_FILTER preference=LIGHT_TASTE matched={}",
                    response.getSessionId(), hardMatched.size());
        }
        metrics.setHardMatchedCandidateCount(hardMatched.size());
        log.info("[AI][session={}] state=RETRIEVING action=HARD_FILTER initial={} hardMatched={}",
                response.getSessionId(), shops.size(), hardMatched.size());

        List<Long> shopIds = hardMatched.stream().map(Shop::getId).collect(Collectors.toList());
        Map<Long, List<AiReviewDocument>> reviewsByShopId = loadReviewsByShopId(shopIds);
        metrics.setRetrievingDurationMs(System.currentTimeMillis() - retrievingStart);

        SemanticRecallResult semanticResult = SemanticRecallResult.unavailable();
        if (semanticShopRetriever != null) {
            String semanticQuery = semanticRetrievalQuery(request, constraints);
            log.info("[AI][session={}] state=SEMANTIC_RETRIEVING action=GEO_TOKENS_PRUNED originalQuery={} semanticQuery={}",
                    response.getSessionId(), compact(request.getQuery()), compact(semanticQuery));
            semanticResult = semanticShopRetriever.recall(semanticQuery, hardMatched, profileByShopId, reviewsByShopId);
            metrics.setSemanticRetrievalUsed(semanticResult.isAvailable());
            metrics.setSemanticRetrievingDurationMs(semanticResult.getDurationMs());
        }

        long rerankingStart = System.currentTimeMillis();
        List<DecisionRecommendation> recommendations = new ArrayList<>();
        for (Shop shop : hardMatched) {
            DecisionRecommendation item = toRecommendation(shop, profileByShopId.get(shop.getId()),
                    reviewsByShopId.get(shop.getId()), request, constraints);
            Double semanticScore = semanticResult.getShopScores().get(shop.getId());
            if (semanticScore != null) {
                item.setSemanticScore(round(semanticScore));
                item.setScore(round(Math.min(100D, item.getScore() + semanticScore * semanticWeight)));
                item.getMatchedReasons().add("语义证据与本轮需求相关");
            }
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

    private Map<Long, List<AiReviewDocument>> loadReviewsByShopId(List<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) return new HashMap<>();
        List<AiReviewDocument> documents = reviewMapper.selectList(new QueryWrapper<AiReviewDocument>()
                .in("shop_id", shopIds));
        return documents.stream().collect(Collectors.groupingBy(AiReviewDocument::getShopId));
    }

    private boolean requiresLightTasteEvidence(DecisionConstraints constraints) {
        return constraints.getSoftPreferences().contains("口味清淡");
    }

    private boolean hasLightTasteEvidence(List<AiReviewDocument> documents) {
        if (documents == null) return false;
        for (AiReviewDocument document : documents) {
            String content = document.getContent() == null ? "" : document.getContent();
            if (content.contains("清淡") || content.contains("不油腻") || content.contains("清爽") || content.contains("少油")) return true;
        }
        return false;
    }

    private boolean matchesHardConstraints(Shop shop, AiShopProfile profile, DecisionRequest request, DecisionConstraints constraints) {
        if (constraints.getBudgetPerPerson() > 0 && (shop.getAvgPrice() == null || shop.getAvgPrice() > constraints.getBudgetPerPerson())) return false;
        if (!constraints.getCuisine().isEmpty() && (profile == null || !matchesCuisine(profile.getCuisine(), constraints.getCuisine()))) return false;
        if (constraints.getRadiusKm() > 0 && request.getLatitude() != null && request.getLongitude() != null
                && distanceKm(request.getLatitude(), request.getLongitude(), shop.getY(), shop.getX()) > constraints.getRadiusKm()) return false;
        return isOpenAt(shop.getOpenHours(), constraints.getArrivalTime());
    }

    private boolean hasAdministrativeScope(DecisionRequest request) {
        return hasText(request.getProvince()) || hasText(request.getCity()) || hasText(request.getDistrict());
    }

    /** City suffixes are presentation variants, unlike districts which remain exact scopes. */
    private List<String> administrativeNameAliases(String city) {
        String normalized = city == null ? "" : city.trim();
        if (normalized.isEmpty()) return Collections.emptyList();
        List<String> aliases = new ArrayList<>();
        aliases.add(normalized);
        if (normalized.endsWith("市") && normalized.length() > 1) {
            aliases.add(normalized.substring(0, normalized.length() - 1));
        } else {
            aliases.add(normalized + "市");
        }
        return aliases;
    }

    /** Geography is consumed by SQL filters and must not become a cuisine-style semantic signal. */
    private String semanticRetrievalQuery(DecisionRequest request, DecisionConstraints constraints) {
        String query = request.getQuery() == null ? "" : request.getQuery();
        query = removeToken(query, request.getProvince());
        query = removeToken(query, request.getCity());
        query = removeToken(query, request.getDistrict());
        query = removeToken(query, constraints == null ? null : constraints.getTargetCity());
        query = removeToken(query, constraints == null ? null : constraints.getTargetArea());
        query = query.replaceAll("\\s+", " ").trim();
        if (hasText(query)) return query;
        return constraints != null && hasText(constraints.getKeyword()) ? constraints.getKeyword() : request.getQuery();
    }

    private String removeToken(String source, String token) {
        return hasText(token) ? source.replace(token, " ") : source;
    }

    private boolean matchesCuisine(String profileCuisine, String requestedCuisine) {
        if (contains(profileCuisine, requestedCuisine)) return true;
        boolean requestedGrill = requestedCuisine.contains("烧烤") || requestedCuisine.contains("烤肉");
        boolean profileGrill = profileCuisine.contains("烧烤") || profileCuisine.contains("烤肉");
        return requestedGrill && profileGrill;
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
        if (requiresLightTasteEvidence(constraints) && hasLightTasteEvidence(documents)) {
            score += 12D;
            item.getMatchedReasons().add("评价证据表明口味清淡");
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
                // Source type remains available to internal retrieval and audit paths, but is not user-facing evidence.
                item.getEvidence().add(document.getContent());
                score += 3D;
            }
        }
        item.setScore(round(Math.min(100D, score)));
        return item;
    }

    private String generateAnswer(Long sessionId, String query, DecisionConstraints constraints, List<DecisionRecommendation> items,
                                  DecisionMetrics metrics, RelaxationInfo relaxation) {
        if (items.isEmpty()) {
            return "当前数据中没有找到满足硬约束的商户。可以适当放宽预算、距离或菜系后再试。";
        }
        String narrative = "该选择在当前硬约束下的综合匹配度最高。";
        if (!aiProperties.isConfigured() || !Boolean.TRUE.equals(aiProperties.getNarrativeEnabled())) {
            return buildFactAnswer(constraints, items, narrative, relaxation);
        }
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(message("system", "你是消费决策助手。仅输出一到两句泛化的取舍建议，不得出现店名、数字、价格、距离、地址、营业时间、评分、证据原文或未提供的事实。"));
            messages.add(message("user", "用户需求：" + query + "\n已满足的偏好：" + objectMapper.writeValueAsString(items.get(0).getMatchedReasons())));
            String generated = springAiTextClient.chatText(messages, "NARRATIVE_GENERATION").trim();
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
        return buildFactAnswer(constraints, items, narrative, relaxation);
    }

    private String buildFactAnswer(DecisionConstraints constraints, List<DecisionRecommendation> items, String narrative,
                                   RelaxationInfo relaxation) {
        DecisionRecommendation first = items.get(0);
        StringBuilder answer = new StringBuilder();
        if (relaxation != null && Boolean.TRUE.equals(relaxation.getAutomatic())) {
            answer.append("默认附近范围内未找到结果，已在不改变地点、菜系、预算和到店时间等硬条件的前提下扩大搜索范围。\n\n");
        }
        if (relaxation != null && "SPARSE".equals(relaxation.getOutcome())) {
            answer.append("当前严格条件下仅找到 1 家商户，未自动放宽任何用户条件。\n\n");
        }
        answer.append("首选 ").append(first.getShopName()).append("。\n\n匹配依据：");
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
        Long previousAttempts = metricMapper.selectCount(new QueryWrapper<AiDecisionMetric>().eq("session_id", sessionId));
        metric.setAttemptNo(Math.toIntExact(previousAttempts + 1));
        metric.setTotalDurationMs(metrics.getTotalDurationMs());
        metric.setExtractingDurationMs(metrics.getExtractingDurationMs());
        metric.setRetrievingDurationMs(metrics.getRetrievingDurationMs());
        metric.setRerankingDurationMs(metrics.getRerankingDurationMs());
        metric.setAnsweringDurationMs(metrics.getAnsweringDurationMs());
        metric.setModelCallCount(metrics.getModelCallCount());
        metric.setModelSuccessCount(metrics.getModelSuccessCount());
        metric.setModelFailureCount(metrics.getModelFailureCount());
        metric.setPromptTokenCount(metrics.getPromptTokenCount());
        metric.setCompletionTokenCount(metrics.getCompletionTokenCount());
        metric.setInitialCandidateCount(metrics.getInitialCandidateCount());
        metric.setHardMatchedCandidateCount(metrics.getHardMatchedCandidateCount());
        metric.setFinalCandidateCount(metrics.getFinalCandidateCount());
        metric.setStrictCandidateCount(metrics.getStrictCandidateCount());
        metric.setRelaxationCount(metrics.getRelaxationCount());
        metric.setAutomaticRelaxationApplied(metrics.getAutomaticRelaxationApplied());
        metric.setResultEvaluationOutcome(metrics.getResultEvaluationOutcome());
        metric.setEvidenceCoveredCandidateCount(metrics.getEvidenceCoveredCandidateCount());
        metric.setEvidenceCoverageRate(metrics.getEvidenceCoverageRate());
        metric.setFactualConsistent(metrics.getFactualConsistent());
        metric.setNarrativeRejected(metrics.getNarrativeRejected());
        metricMapper.insert(metric);
    }

    private void recordStep(DecisionResponse response, Long sessionId, String state, String summary, long startedAt) {
        long duration = Math.max(0L, System.currentTimeMillis() - startedAt);
        response.getTrace().add(new DecisionTraceItem(state, summary, duration));
        recordRuntimeStep(sessionId, state, summary, duration);
    }

    private void recordCompletedStep(DecisionResponse response, Long sessionId, String state, String summary, Long durationMs) {
        long duration = durationMs == null ? 0L : durationMs;
        response.getTrace().add(new DecisionTraceItem(state, summary, duration));
        recordRuntimeStep(sessionId, state, summary, duration);
    }

    private void recordRuntimeStep(Long sessionId, String state, String summary, long duration) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("decisionSessionId", sessionId); result.put("state", state);
        result.put("summary", summary); result.put("durationMs", duration);
        if (conversationEventService != null) {
            conversationEventService.record(eventTypeForStep(state), ConversationEventStatus.SUCCESS, null, null, result, null);
        }
    }

    private ConversationEventType eventTypeForStep(String state) {
        if ("RETRIEVING".equals(state) || "SEMANTIC_RETRIEVING".equals(state) || "RERANKING".equals(state)) return ConversationEventType.RETRIEVAL;
        if ("RESULT_EVALUATING".equals(state)) return ConversationEventType.RESULT_EVALUATION;
        if ("WAITING_RELAXATION".equals(state)) return ConversationEventType.AUTO_RELAXATION;
        if ("CLARIFYING".equals(state)) return ConversationEventType.POLICY_DECISION;
        return ConversationEventType.DECISION_STARTED;
    }

    private Long asLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return null;
        try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ignored) { return null; }
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
