package com.hmdp.ai.v2.runtime;
import com.hmdp.ai.v2.plan.*;
import com.hmdp.ai.v2.semantic.*;
import com.hmdp.ai.mapper.*;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class SearchRadiusPolicyTest {
    final ShopMapper shops=mock(ShopMapper.class);
    final ShopRetrievalEngine engine=new ShopRetrievalEngine();
    SearchRadiusPolicyTest(){
        var profiles=mock(AiShopProfileMapper.class);var reviews=mock(AiReviewDocumentMapper.class);
        when(profiles.selectList(any())).thenReturn(List.of());when(reviews.selectList(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(engine,"shopMapper",shops);ReflectionTestUtils.setField(engine,"profileMapper",profiles);ReflectionTestUtils.setField(engine,"reviewMapper",reviews);
    }
    Shop shop(long id,double lon){var s=new Shop();s.setId(id);s.setName("TEST_ONLY"+id);s.setAvgPrice(40L);s.setX(lon);s.setY(0D);return s;}
    SearchSpec spec(boolean hard,SearchAnchor anchor){var c=new DiningCriteria(new DiningCriteria.LocationCriteria(null,null,"附近"),null,null,
            hard?new DiningCriteria.DistanceCriteria(BigDecimal.valueOf(3)):null,null,DiningCriteria.SemanticPreferences.empty());
        return new SearchSpec(1,"test",ExecutionAction.SearchKind.RECOMMENDATIONS,3,c,List.of(),Set.of(),null,anchor);}
    SearchAnchor anchor(){return new SearchAnchor(null,"TEST_ONLY device",0D,0D,null,null,null,SearchAnchor.AnchorSource.DEVICE);}
    @Test void defaultThreeKmFiltersBeforeRecallWithoutMutatingUserCriteria(){
        when(shops.selectList(any())).thenReturn(List.of(shop(1,.018),shop(2,.036),shop(3,.054)));
        var input=spec(false,anchor());var result=engine.retrieve(input);assertEquals(List.of(1L),result.candidates().stream().map(s->s.getShopId()).toList());
        assertEquals(3D,result.metrics().appliedRadiusKm());assertNull(input.criteria().distance());assertEquals(SearchRadiusPolicy.Source.SYSTEM_DEFAULT,result.metrics().radiusSource());
    }
    @Test void onlyEmptyDefaultScopeExpandsOnceToFiveAndNeverBeyond(){
        when(shops.selectList(any())).thenReturn(List.of(shop(2,.036),shop(3,.054)));
        var result=engine.retrieve(spec(false,anchor()));assertEquals(List.of(2L),result.candidates().stream().map(s->s.getShopId()).toList());assertEquals(5D,result.metrics().appliedRadiusKm());
        verify(shops,times(1)).selectList(any());
    }
    @Test void explicitThreeKmIsNeverExpanded(){
        when(shops.selectList(any())).thenReturn(List.of(shop(2,.036)));
        var result=engine.retrieve(spec(true,anchor()));assertTrue(result.candidates().isEmpty());assertEquals(3D,result.metrics().appliedRadiusKm());assertEquals(SearchRadiusPolicy.Source.USER_EXPLICIT,result.metrics().radiusSource());
    }
    @Test void missingOrInvalidCoordinatesCannotExecuteDistanceFiltering(){
        for(SearchAnchor a:Arrays.asList(null,new SearchAnchor(null,null,Double.NaN,0D,null,null,null,SearchAnchor.AnchorSource.DEVICE))){
            assertFalse(engine.retrieve(spec(false,a)).supported());assertFalse(engine.retrieve(spec(true,a)).supported());
        }
        verifyNoInteractions(shops);
    }
}
