package com.hmdp.ai.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.dto.LocationResolutionContext;
import com.hmdp.ai.dto.LocationResolutionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class AmapPoiSearchProviderTest {

    @Test
    void parsesCanonicalPoiIdentityAndCampusLabel() {
        AmapPoiSearchProvider provider = new AmapPoiSearchProvider();
        ReflectionTestUtils.setField(provider, "objectMapper", new ObjectMapper());
        String body = "{\"status\":\"1\",\"pois\":[{"
                + "\"id\":\"B0FFFAKE\",\"name\":\"福建理工大学旗山校区\","
                + "\"location\":\"119.205,26.052\",\"pname\":\"福建省\","
                + "\"cityname\":\"福州市\",\"adname\":\"闽侯县\","
                + "\"type\":\"教育学校\",\"typecode\":\"141201\","
                + "\"business\":{\"business_area\":\"旗山校区\"}}]}";

        List<ResolvedLocationCandidate> result = provider.parseResponse(body, "理工大学");

        assertEquals(1, result.size());
        ResolvedLocationCandidate candidate = result.get(0);
        assertEquals("B0FFFAKE", candidate.getPoiId());
        assertEquals("福建理工大学旗山校区", candidate.getCanonicalName());
        assertEquals("旗山校区", candidate.getCampusLabel());
        assertEquals("教育学校", candidate.getPoiType());
        assertEquals("141201", candidate.getPoiTypeCode());
        assertEquals("福建省", candidate.getProvince());
        assertEquals("福州市", candidate.getCity());
        assertEquals("闽侯县", candidate.getDistrict());
        assertNotNull(candidate.getLatitude());
        assertEquals("AMAP_POI", candidate.getSource());
    }

    @Test
    void universityHintRejectsNearbyMerchantCandidates() {
        AmapPoiSearchProvider provider = new AmapPoiSearchProvider();
        String body = "{\"status\":\"1\",\"pois\":["
                + "{\"id\":\"restaurant\",\"name\":\"师大分店\",\"type\":\"餐饮服务\",\"typecode\":\"050000\",\"location\":\"119.2,26.0\"},"
                + "{\"id\":\"primary-school\",\"name\":\"福建师范大学附属小学\",\"type\":\"教育学校\",\"typecode\":\"141204\",\"location\":\"119.205,26.005\"},"
                + "{\"id\":\"school\",\"name\":\"福建师范大学\",\"type\":\"教育学校\",\"typecode\":\"141201\",\"location\":\"119.21,26.01\"}]}";

        List<ResolvedLocationCandidate> result = provider.parseResponse(body, "师大", "UNIVERSITY");

        assertEquals(1, result.size());
        assertEquals("school", result.get(0).getPoiId());
    }

    @Test
    void usesActiveCityAndDeviceOnlyAsRankingPrior() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"status\":\"1\",\"pois\":["
                + "{\"id\":\"far\",\"name\":\"福建理工大学鼓山校区\",\"location\":\"119.40,26.10\",\"pname\":\"福建省\",\"cityname\":\"福州市\",\"adname\":\"晋安区\"},"
                + "{\"id\":\"near\",\"name\":\"福建理工大学旗山校区\",\"location\":\"119.20,26.05\",\"pname\":\"福建省\",\"cityname\":\"福州市\",\"adname\":\"闽侯县\"}]}" );
        doReturn(response).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        AmapPoiSearchProvider provider = new AmapPoiSearchProvider(client, new ObjectMapper());
        ReflectionTestUtils.setField(provider, "enabled", true);
        ReflectionTestUtils.setField(provider, "apiKey", "test-key");
        ReflectionTestUtils.setField(provider, "endpoint", "https://restapi.amap.com/v5/place/text");

        LocationResolutionContext context = new LocationResolutionContext();
        context.setActiveCity("福州市");
        context.setDeviceLatitude(26.05D);
        context.setDeviceLongitude(119.20D);
        List<ResolvedLocationCandidate> result = provider.resolve(new LocationResolutionRequest("理工大学", context));

        assertEquals("福建理工大学旗山校区", result.get(0).getCanonicalName());
        HttpRequest request = org.mockito.Mockito.mockingDetails(client).getInvocations().stream()
                .findFirst().orElseThrow().getArgument(0);
        assertEquals(true, request.uri().toString().contains("region=%E7%A6%8F%E5%B7%9E%E5%B8%82"));
        assertEquals(true, request.uri().toString().contains("keywords=%E7%90%86%E5%B7%A5%E5%A4%A7%E5%AD%A6"));
    }

    @Test
    void usesAroundSearchWhenOnlyDeviceLocationIsAvailable() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"status\":\"1\",\"pois\":["
                + "{\"id\":\"poi-1\",\"name\":\"福建农林大学旗山校区\","
                + "\"location\":\"119.20,26.05\",\"cityname\":\"福州市\"}]}");
        doReturn(response).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        AmapPoiSearchProvider provider = new AmapPoiSearchProvider(client, new ObjectMapper());
        ReflectionTestUtils.setField(provider, "enabled", true);
        ReflectionTestUtils.setField(provider, "apiKey", "test-key");

        LocationResolutionContext context = new LocationResolutionContext();
        context.setDeviceLatitude(26.05D);
        context.setDeviceLongitude(119.20D);
        List<ResolvedLocationCandidate> result = provider.resolve(new LocationResolutionRequest("农大", context));

        assertEquals(1, result.size());
        HttpRequest request = org.mockito.Mockito.mockingDetails(client).getInvocations().stream()
                .findFirst().orElseThrow().getArgument(0);
        assertEquals(true, request.uri().toString().contains("/v5/place/around"));
        assertEquals(true, request.uri().toString().contains("location=119.2%2C26.05"));
        assertEquals(true, request.uri().toString().contains("radius=50000"));
    }
}
