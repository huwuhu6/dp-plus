package com.hmdp.ai.service;

import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.RelaxationInfo;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a retrieval result is sufficient and exposes the one safe
 * automatic relaxation available in the current product: expanding a system
 * default nearby radius. It never changes an explicitly stated constraint.
 */
@Service
public class ResultEvaluationService {
    private static final String DEFAULT_NEARBY_MARKER = "\u201c附近\u201d按默认 3km 解释";

    @Resource private AiProperties aiProperties;

    public RelaxationInfo evaluateStrictResult(DecisionRequest request, DecisionConstraints constraints,
                                               int candidateCount) {
        RelaxationInfo result = new RelaxationInfo();
        result.setStrictCandidateCount(candidateCount);
        result.setRelaxedCandidateCount(candidateCount);
        result.setPreservedHardConstraints(preservedHardConstraints(request, constraints));
        if (candidateCount > 1) {
            result.setOutcome("SUFFICIENT");
        } else if (candidateCount == 1) {
            result.setOutcome("SPARSE");
            result.setReason("严格条件下仅找到 1 家商户，未自动放宽任何用户条件。");
        } else {
            result.setOutcome("EMPTY");
            result.setReason("严格条件下未找到商户。");
        }
        return result;
    }

    public boolean applySafeAutomaticRelaxation(DecisionRequest request, DecisionConstraints constraints,
                                                 RelaxationInfo result) {
        if (!"EMPTY".equals(result.getOutcome()) || !canExpandDefaultNearbyRadius(request, constraints)) {
            return false;
        }
        double before = constraints.getRadiusKm();
        double after = aiProperties.getResultEvaluation().getAutoExpandedNearbyRadiusKm();
        constraints.setRadiusKm(after);
        constraints.getSoftPreferences().remove(DEFAULT_NEARBY_MARKER);
        constraints.getSoftPreferences().add("系统默认附近范围已从 " + format(before) + "km 扩展至 " + format(after) + "km");
        result.setAutomatic(true);
        result.setOutcome("AUTO_RELAXED");
        result.setReason("未指定具体距离时，默认附近范围内无结果；仅扩大系统默认搜索半径。");
        result.getAppliedChanges().add("radiusKm:" + format(before) + "->" + format(after) + " (SYSTEM_DEFAULT)");
        return true;
    }

    public void recordRelaxedResult(RelaxationInfo result, int candidateCount) {
        result.setRelaxedCandidateCount(candidateCount);
        if (candidateCount == 0) {
            result.setOutcome("WAITING_USER_RELAXATION");
            result.setReason("系统默认附近范围已扩展一次仍无结果，等待用户明确放宽条件。");
        }
    }

    private boolean canExpandDefaultNearbyRadius(DecisionRequest request, DecisionConstraints constraints) {
        AiProperties.ResultEvaluationProperties policy = aiProperties.getResultEvaluation();
        if (!Boolean.TRUE.equals(policy.getAutoExpandDefaultNearbyRadius())) return false;
        if (!Boolean.TRUE.equals(constraints.getNearby())) return false;
        if (request.getLatitude() == null || request.getLongitude() == null) return false;
        if (constraints.getRadiusKm() == null || constraints.getRadiusKm() <= 0D) return false;
        if (constraints.getRadiusKm() >= policy.getAutoExpandedNearbyRadiusKm()) return false;
        return constraints.getSoftPreferences().contains(DEFAULT_NEARBY_MARKER);
    }

    private List<String> preservedHardConstraints(DecisionRequest request, DecisionConstraints constraints) {
        List<String> values = new ArrayList<String>();
        if (request.getLatitude() != null && request.getLongitude() != null) values.add("location");
        if (hasText(request.getProvince())) values.add("province=" + request.getProvince());
        if (hasText(request.getCity())) values.add("city=" + request.getCity());
        if (hasText(request.getDistrict())) values.add("district=" + request.getDistrict());
        if (hasText(constraints.getCuisine())) values.add("cuisine=" + constraints.getCuisine());
        if (constraints.getBudgetPerPerson() != null && constraints.getBudgetPerPerson() > 0) values.add("budgetPerPerson=" + constraints.getBudgetPerPerson());
        if (hasText(constraints.getArrivalTime())) values.add("arrivalTime=" + constraints.getArrivalTime());
        values.addAll(constraints.getHardConstraints());
        return values;
    }

    private String format(double value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
