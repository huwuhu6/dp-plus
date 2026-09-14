package com.hmdp.ai.v2.evidence;

import java.util.List;

/** Read-only research output. Runtime traces remain outside durable decision evidence. */
public record EvidenceBundle(List<DecisionReason> reasons) { public EvidenceBundle { reasons = List.copyOf(reasons); } }
