package com.hmdp.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DecisionConstraints {
    /** User's explicit search destination. It is never a substitute for device location. */
    private String targetCity = "";
    private String targetArea = "";
    /** Explicit destination, current device, or unspecified location intent. */
    private String locationIntent = "UNSPECIFIED";
    /** Named entity the user explicitly asks for (shop name / signature dish / specific food), after cuisine & geographic slots are consumed. Free text; not hard-filtered today (see #33 for deterministic exact-match path). */
    private String keyword = "";
    /** Cuisine slot — canonicalized to the closed set in CuisineCanonicalizer (Meituan/Dianping-level categories + "其他" fallback). Participates in hard filter. */
    private String cuisine = "";
    private Integer budgetPerPerson = -1;
    /** Relative budget intent (critique): -1 = cheaper (太贵/好贵/平价/便宜点/实惠), 0 = none, 1 = more expensive. One-shot; reset to 0 after merge applies it. */
    private Integer budgetDirection = 0;
    private Double radiusKm = -1D;
    /** Relative distance intent (critique): -1 = closer (更近/近一点), 0 = none, 1 = farther. One-shot; reset after merge applies it. */
    private Integer radiusDirection = 0;
    /** "附近" semantics: triggers default radius 3km and location dependency. */
    private Boolean nearby = false;
    private String arrivalTime = "";
    /** Open-set soft preferences as natural-language tags (安静/不排队/约会/便餐/清淡/辣/适合聚餐/氛围好...). Consumed via rule map + semantic enhancement; never hard-filtered. */
    private List<String> preferences = new ArrayList<>();
    /** System interpretation notes (默认3km解释/范围扩展/按位置检索) — display + audit only, not user preferences. */
    private List<String> systemNotes = new ArrayList<>();
    private List<String> missingInformation = new ArrayList<>();
    private Set<String> lockedConstraints = new HashSet<>();
    /** Constraint fields the user explicitly abandons (e.g. "看看有没有别的吃的" clears cuisine/keyword). */
    private List<String> clearedFields = new ArrayList<>();
    /** Explicit preference removals, extracted as structured delta rather than inferred by merger text rules. */
    private List<String> removedPreferences = new ArrayList<>();
}
