package com.hmdp.ai.service;

import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionOption;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.RelaxationInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the deterministic facts shown when a decision has no executable result.
 * Both the persisted WAITING response and a later explanation query use this
 * formatter so scope, constraints and relaxation history cannot drift apart.
 */
@Component
public class DecisionFailureExplanationFormatter {

    public String format(DecisionResponse response) {
        return format(response, null);
    }

    /** Formats failure facts with the canonical named search location when one was confirmed. */
    public String format(DecisionResponse response, String canonicalLocationName) {
        if (response == null) return "当前没有可解释的搜索结果。";
        DecisionConstraints constraints = response.getConstraints();
        String scope = searchScope(constraints, canonicalLocationName);
        String conditions = foodConditions(constraints);
        int resultCount = response.getRecommendations() == null ? 0 : response.getRecommendations().size();
        StringBuilder answer = new StringBuilder("我刚才按").append(scope);
        if (hasText(conditions)) answer.append("，保留").append(conditions).append("条件");
        answer.append("搜索，当前找到").append(resultCount).append("家匹配商户。");

        RelaxationInfo relaxation = response.getRelaxation();
        if (relaxation != null && Boolean.TRUE.equals(relaxation.getAutomatic())) {
            String note = automaticRelaxationNote(constraints);
            answer.append("系统已自动扩大过一次默认附近范围");
            if (hasText(note)) answer.append("（").append(note).append("）");
            answer.append("，扩大后仍未找到匹配结果。");
        }

        if ("ZERO_RESULT_NO_DATA".equals(response.getStatus())) {
            return answer.append("该范围暂无入库商户。你可以切换城市或周边区域后再搜。").toString();
        }

        List<String> choices = new ArrayList<>();
        if (response.getOptions() != null) {
            for (DecisionOption option : response.getOptions()) {
                if (option != null && !"END_DECISION".equals(option.getId()) && hasText(option.getLabel())) {
                    choices.add(option.getLabel());
                }
            }
        }
        if (!choices.isEmpty()) answer.append("你可以").append(String.join("；", choices)).append("。");
        else answer.append("当前没有可执行的放宽选项。");
        return answer.toString();
    }

    private String searchScope(DecisionConstraints constraints, String canonicalLocationName) {
        if (constraints == null) return "当前搜索范围";
        if (hasText(canonicalLocationName)) {
            String scope = canonicalLocationName + "附近";
            if (constraints.getRadiusKm() != null && constraints.getRadiusKm() > 0D) {
                scope += " " + formatDistance(constraints.getRadiusKm()) + "km";
            }
            return scope;
        }
        String named = hasText(constraints.getTargetArea()) ? constraints.getTargetArea()
                : (hasText(constraints.getTargetDistrict()) ? constraints.getTargetDistrict()
                : (hasText(constraints.getTargetCity()) ? constraints.getTargetCity() : constraints.getTargetProvince()));
        String base;
        if (hasText(named)) {
            base = named;
        } else if ("CURRENT_DEVICE".equalsIgnoreCase(constraints.getLocationIntent()) || Boolean.TRUE.equals(constraints.getNearby())) {
            base = "当前位置附近";
        } else {
            base = "当前搜索范围";
        }
        if (constraints.getRadiusKm() != null && constraints.getRadiusKm() > 0D) {
            base += " " + formatDistance(constraints.getRadiusKm()) + "km";
        }
        return base;
    }

    private String foodConditions(DecisionConstraints constraints) {
        if (constraints == null) return "";
        List<String> values = new ArrayList<>();
        if (hasText(constraints.getKeyword())) values.add("“" + constraints.getKeyword() + "”");
        if (hasText(constraints.getCuisine())) values.add("“" + constraints.getCuisine() + "”");
        if (constraints.getBudgetPerPerson() != null && constraints.getBudgetPerPerson() > 0) {
            values.add("人均预算" + constraints.getBudgetPerPerson() + "元");
        }
        if (constraints.getPreferences() != null) {
            for (String preference : constraints.getPreferences()) {
                if (hasText(preference)) values.add("偏好“" + preference + "”");
            }
        }
        return String.join("/", values);
    }

    private String automaticRelaxationNote(DecisionConstraints constraints) {
        if (constraints == null || constraints.getSystemNotes() == null) return "";
        for (String note : constraints.getSystemNotes()) {
            if (note != null && note.contains("系统默认附近范围已从")) return note;
        }
        return "";
    }

    private String formatDistance(Double value) {
        if (value == null) return "";
        return value == Math.rint(value) ? String.valueOf(value.intValue()) : String.valueOf(value);
    }

    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
