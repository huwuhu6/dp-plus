package com.hmdp.ai.v2;

import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.LocationResolutionRequest;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.service.LocationResolutionProvider;
import com.hmdp.ai.v2.grounding.V2LocationResolver;
import com.hmdp.ai.v2.plan.SearchAnchor;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class V2LocationResolverTest {
    @Test
    void deviceCoordinatesBecomeAnchorAndHardDistanceWithoutCoordinatesClarifies() {
        V2LocationResolver resolver = new V2LocationResolver(null);
        ChatLocationInput location = new ChatLocationInput(); location.setLatitude(26.08); location.setLongitude(119.3);
        var resolved = (V2LocationResolver.Resolution.Resolved) resolver.resolve(criteria(null,
                new DiningCriteria.DistanceCriteria(BigDecimal.valueOf(3))), location, null);
        assertEquals(SearchAnchor.AnchorSource.DEVICE, resolved.anchor().source());
        assertEquals(26.08, resolved.anchor().latitude());
        assertInstanceOf(V2LocationResolver.Resolution.NeedsClarification.class,
                resolver.resolve(criteria(null, new DiningCriteria.DistanceCriteria(BigDecimal.valueOf(3))), null, null));
    }

    @Test
    void namedPoiIsResolvedAndAmbiguousPoiRequiresClarification() {
        LocationResolutionProvider provider = mock(LocationResolutionProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.resolve(any(LocationResolutionRequest.class))).thenReturn(List.of(candidate("poi-1", "三坊七巷", "福州市")));
        V2LocationResolver resolver = new V2LocationResolver(provider);
        var resolved = resolver.resolve(criteria(new DiningCriteria.LocationCriteria("福州市", null, "三坊七巷"), null), null, null);
        assertInstanceOf(V2LocationResolver.Resolution.Resolved.class, resolved);
        SearchAnchor anchor = ((V2LocationResolver.Resolution.Resolved) resolved).anchor();
        assertEquals(SearchAnchor.AnchorSource.POI, anchor.source());
        assertEquals("福州市", anchor.city()); assertEquals(119.3, anchor.longitude());
        when(provider.resolve(any(LocationResolutionRequest.class))).thenReturn(List.of(candidate("1", "大学城东区", "福州市"), candidate("2", "大学城西区", "福州市")));
        assertInstanceOf(V2LocationResolver.Resolution.NeedsClarification.class,
                resolver.resolve(criteria(new DiningCriteria.LocationCriteria(null, null, "大学城"), null), null, null));
    }

    @Test
    void explicitAdministrativeScopeIsPreservedSeparatelyFromPreviousExecutionAnchor() {
        V2LocationResolver resolver = new V2LocationResolver(null);
        SearchAnchor previous = new SearchAnchor("poi", "旧地点", 1D, 1D, null, "旧城市", null, SearchAnchor.AnchorSource.POI);
        var resolved = (V2LocationResolver.Resolution.Resolved) resolver.resolve(
                criteria(new DiningCriteria.LocationCriteria("福州市", "鼓楼区", null), null), null, previous);
        assertNull(resolved.anchor().latitude());
        assertEquals("福州市", resolved.anchor().city());
        assertEquals("鼓楼区", resolved.anchor().district());
    }

    private DiningCriteria criteria(DiningCriteria.LocationCriteria location, DiningCriteria.DistanceCriteria distance) {
        return new DiningCriteria(location, null, null, distance, null, DiningCriteria.SemanticPreferences.empty());
    }
    private ResolvedLocationCandidate candidate(String id, String name, String city) {
        ResolvedLocationCandidate candidate = new ResolvedLocationCandidate(); candidate.setPoiId(id);
        candidate.setCanonicalName(name); candidate.setCity(city); candidate.setLatitude(26.08); candidate.setLongitude(119.3); return candidate;
    }
}
