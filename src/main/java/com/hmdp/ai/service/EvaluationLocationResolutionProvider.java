package com.hmdp.ai.service;

import com.hmdp.ai.dto.ResolvedLocationCandidate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * External-resolution fixture for the eval profile. It models only the provider
 * contract; it does not inspect case codes or mutate conversation state.
 */
@Service
@Profile("eval")
public class EvaluationLocationResolutionProvider implements LocationResolutionProvider {
    private static final Map<String, List<ResolvedLocationCandidate>> FIXTURES = createFixtures();

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<ResolvedLocationCandidate> resolve(String placeText) {
        if (placeText == null) return Collections.emptyList();
        String query = placeText.trim();
        return FIXTURES.getOrDefault(query, Collections.emptyList());
    }

    private static Map<String, List<ResolvedLocationCandidate>> createFixtures() {
        EvaluationLocationResolutionProvider provider = new EvaluationLocationResolutionProvider();
        return Map.of(
                "福州大学", List.of(provider.candidate("福州大学", "福建省", "福州市", "闽侯县", 26.0606, 119.1836),
                        provider.candidate("福州大学（旗山校区）", "福建省", "福州市", "闽侯县", 26.0500, 119.1700)),
                "福州鼓楼", List.of(provider.candidate("福州市鼓楼区", "福建省", "福州市", "鼓楼区", 26.0823, 119.3062)),
                "福州鼓楼区", List.of(provider.candidate("福州市鼓楼区", "福建省", "福州市", "鼓楼区", 26.0823, 119.3062)),
                "我在福州鼓楼，", List.of(provider.candidate("福州市鼓楼区", "福建省", "福州市", "鼓楼区", 26.0823, 119.3062)),
                "福州", List.of(provider.candidate("福州市", "福建省", "福州市", null, 26.0745, 119.2965))
        );
    }

    private ResolvedLocationCandidate candidate(String label, String province, String city, String district,
                                                 double latitude, double longitude) {
        ResolvedLocationCandidate candidate = new ResolvedLocationCandidate();
        candidate.setLabel(label); candidate.setProvince(province); candidate.setCity(city);
        candidate.setDistrict(district); candidate.setLatitude(latitude); candidate.setLongitude(longitude);
        candidate.setSource("EVAL_FIXTURE");
        return candidate;
    }
}
