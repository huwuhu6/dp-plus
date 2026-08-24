package com.hmdp.ai.service;

import com.hmdp.ai.dto.CriteriaMergeResult;
import com.hmdp.ai.dto.DecisionConstraints;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Applies an explicitly expressed constraint delta without asking the model to infer absent fields. */
@Service
public class ConversationCriteriaMerger {

    public CriteriaMergeResult merge(DecisionConstraints previous, DecisionConstraints delta, String query) {
        DecisionConstraints merged = copy(previous);
        CriteriaMergeResult result = new CriteriaMergeResult();
        result.setConstraints(merged);
        String text = query == null ? "" : query.replaceAll("\\s+", "");

        inherit(result, previous);
        if (hasText(delta.getCuisine())) replace(result, "cuisine", merged.getCuisine(), delta.getCuisine(), () -> merged.setCuisine(delta.getCuisine()));
        if (delta.getBudgetPerPerson() != null && delta.getBudgetPerPerson() > 0) replace(result, "budgetPerPerson", String.valueOf(merged.getBudgetPerPerson()), String.valueOf(delta.getBudgetPerPerson()), () -> merged.setBudgetPerPerson(delta.getBudgetPerPerson()));
        if (delta.getRadiusKm() != null && delta.getRadiusKm() > 0) replace(result, "radiusKm", String.valueOf(merged.getRadiusKm()), String.valueOf(delta.getRadiusKm()), () -> merged.setRadiusKm(delta.getRadiusKm()));
        if (hasText(delta.getArrivalTime())) replace(result, "arrivalTime", merged.getArrivalTime(), delta.getArrivalTime(), () -> merged.setArrivalTime(delta.getArrivalTime()));
        if (hasText(delta.getOccasion())) replace(result, "occasion", merged.getOccasion(), delta.getOccasion(), () -> merged.setOccasion(delta.getOccasion()));
        if (Boolean.TRUE.equals(delta.getQuiet())) replace(result, "quiet", String.valueOf(merged.getQuiet()), "true", () -> merged.setQuiet(true));
        if (Boolean.TRUE.equals(delta.getAvoidQueue())) replace(result, "avoidQueue", String.valueOf(merged.getAvoidQueue()), "true", () -> merged.setAvoidQueue(true));
        if (Boolean.TRUE.equals(delta.getNearby())) replace(result, "nearby", String.valueOf(merged.getNearby()), "true", () -> merged.setNearby(true));

        if (containsAny(text, "不限菜系", "什么都行", "随便吃", "不限制菜系")) clear(result, "cuisine", () -> merged.setCuisine(""));
        if (containsAny(text, "预算不限", "不限制预算", "人均不限")) clear(result, "budgetPerPerson", () -> merged.setBudgetPerPerson(-1));
        if (containsAny(text, "不限距离", "不考虑距离", "全城都行")) {
            clear(result, "radiusKm", () -> merged.setRadiusKm(-1D));
            clear(result, "nearby", () -> merged.setNearby(false));
        }
        if (containsAny(text, "不要安静", "不用安静")) clear(result, "quiet", () -> merged.setQuiet(false));
        if (containsAny(text, "排队也行", "不用避开排队")) clear(result, "avoidQueue", () -> merged.setAvoidQueue(false));
        if (containsAny(text, "不要辣", "不吃辣", "清淡", "少油")) addPreference(merged, "清淡/不辣");

        merged.setHardConstraints(unique(merged.getHardConstraints()));
        merged.setSoftPreferences(unique(merged.getSoftPreferences()));
        return result;
    }

    private DecisionConstraints copy(DecisionConstraints source) {
        DecisionConstraints target = new DecisionConstraints();
        if (source == null) return target;
        target.setCuisine(source.getCuisine()); target.setBudgetPerPerson(source.getBudgetPerPerson());
        target.setRadiusKm(source.getRadiusKm()); target.setNearby(source.getNearby());
        target.setArrivalTime(source.getArrivalTime()); target.setOccasion(source.getOccasion());
        target.setQuiet(source.getQuiet()); target.setAvoidQueue(source.getAvoidQueue());
        target.setHardConstraints(new ArrayList<String>(source.getHardConstraints() == null ? new ArrayList<String>() : source.getHardConstraints()));
        target.setSoftPreferences(new ArrayList<String>(source.getSoftPreferences() == null ? new ArrayList<String>() : source.getSoftPreferences()));
        target.setMissingInformation(new ArrayList<String>(source.getMissingInformation() == null ? new ArrayList<String>() : source.getMissingInformation()));
        return target;
    }

    private void inherit(CriteriaMergeResult result, DecisionConstraints previous) {
        if (previous == null) return;
        if (hasText(previous.getCuisine())) result.getInherited().add("cuisine=" + previous.getCuisine());
        if (previous.getBudgetPerPerson() != null && previous.getBudgetPerPerson() > 0) result.getInherited().add("budgetPerPerson=" + previous.getBudgetPerPerson());
        if (previous.getRadiusKm() != null && previous.getRadiusKm() > 0) result.getInherited().add("radiusKm=" + previous.getRadiusKm());
        if (hasText(previous.getOccasion())) result.getInherited().add("occasion=" + previous.getOccasion());
    }

    private void replace(CriteriaMergeResult result, String field, String before, String after, Runnable mutation) {
        mutation.run();
        if (!String.valueOf(before).equals(String.valueOf(after))) result.getReplaced().add(field + ":" + before + "->" + after);
    }

    private void clear(CriteriaMergeResult result, String field, Runnable mutation) {
        mutation.run();
        if (!result.getCleared().contains(field)) result.getCleared().add(field);
    }

    private void addPreference(DecisionConstraints constraints, String preference) {
        if (constraints.getSoftPreferences() == null) constraints.setSoftPreferences(new ArrayList<String>());
        constraints.getSoftPreferences().add(preference);
    }

    private List<String> unique(List<String> values) { return new ArrayList<String>(new LinkedHashSet<String>(values == null ? new ArrayList<String>() : values)); }
    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
    private boolean containsAny(String source, String... values) { for (String value : values) if (source.contains(value)) return true; return false; }
}
