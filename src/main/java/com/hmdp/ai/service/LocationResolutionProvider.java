package com.hmdp.ai.service;

import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.dto.LocationResolutionRequest;
import java.util.List;

/** Small port for resolving a user-named location into trusted candidates. */
public interface LocationResolutionProvider {
    boolean isAvailable();
    List<ResolvedLocationCandidate> resolve(String placeText);

    default List<ResolvedLocationCandidate> resolve(LocationResolutionRequest request) {
        return resolve(request == null ? null : request.getRawText());
    }
}
