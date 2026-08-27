package com.hmdp.ai.service;

import com.hmdp.ai.dto.LocalDemoDatasetRequest;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class LocalDemoDatasetServiceTest {
    @Test
    void rejectsGenerationWithoutACompleteCityDefinition() {
        LocalDemoDatasetService service = new LocalDemoDatasetService();
        ReflectionTestUtils.setField(service, "shopMapper", mock(ShopMapper.class));
        ReflectionTestUtils.setField(service, "profileMapper", mock(AiShopProfileMapper.class));
        ReflectionTestUtils.setField(service, "reviewMapper", mock(AiReviewDocumentMapper.class));
        LocalDemoDatasetRequest request = new LocalDemoDatasetRequest();
        LocalDemoDatasetRequest.City city = new LocalDemoDatasetRequest.City();
        city.setProvince("福建省"); city.setCity("福州市"); city.setLongitude(119.2D); city.setLatitude(26.08D);
        city.setDistricts(Collections.<String>emptyList()); request.setCities(Collections.singletonList(city));
        assertThrows(IllegalArgumentException.class, () -> service.generate(request));
    }

    @Test
    void rejectsMalformedCityNamesBeforeWritingSyntheticData() {
        LocalDemoDatasetService service = new LocalDemoDatasetService();
        ReflectionTestUtils.setField(service, "shopMapper", mock(ShopMapper.class));
        ReflectionTestUtils.setField(service, "profileMapper", mock(AiShopProfileMapper.class));
        ReflectionTestUtils.setField(service, "reviewMapper", mock(AiReviewDocumentMapper.class));
        LocalDemoDatasetRequest request = new LocalDemoDatasetRequest();
        LocalDemoDatasetRequest.City city = new LocalDemoDatasetRequest.City();
        city.setProvince("福建省"); city.setCity("???"); city.setLongitude(119.2D); city.setLatitude(26.08D);
        city.setDistricts(Collections.singletonList("鼓楼区")); request.setCities(Collections.singletonList(city));
        assertThrows(IllegalArgumentException.class, () -> service.generate(request));
    }
}
