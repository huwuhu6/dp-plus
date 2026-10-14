package com.hmdp.ai.v2.plan;
import com.hmdp.ai.v2.semantic.DiningCriteria;

/** 系统搜索范围是策略，不写回用户 hardMaxKm；只有默认范围可在无结果时有界扩大一次。 */
public record SearchRadiusPolicy(Source source,Double initialKm,Double maxKm) {
    public enum Source { NONE, SYSTEM_DEFAULT, USER_EXPLICIT }
    public static SearchRadiusPolicy userOnly(DiningCriteria c){return c!=null && c.distance()!=null && c.distance().hardMaxKm()!=null?
            new SearchRadiusPolicy(Source.USER_EXPLICIT,c.distance().hardMaxKm().doubleValue(),c.distance().hardMaxKm().doubleValue()):new SearchRadiusPolicy(Source.NONE,null,null);}
    public static SearchRadiusPolicy forSearch(DiningCriteria c,SearchAnchor a){
        var explicit=userOnly(c);if(explicit.source()!=Source.NONE)return explicit;
        boolean nearby=c!=null && c.location()!=null && c.location().nearby();
        boolean point=a!=null && (a.source()==SearchAnchor.AnchorSource.POI || a.source()==SearchAnchor.AnchorSource.PERSONAL_LOCATION_PROFILE
                || a.source()==SearchAnchor.AnchorSource.DEVICE && (c==null || c.location()==null || c.location().city()==null));
        return nearby||point?new SearchRadiusPolicy(Source.SYSTEM_DEFAULT,3D,5D):explicit;
    }
}
