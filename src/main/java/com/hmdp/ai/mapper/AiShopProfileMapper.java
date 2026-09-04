package com.hmdp.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.ai.entity.AiShopProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AiShopProfileMapper extends BaseMapper<AiShopProfile> {
    @Insert("""
            INSERT INTO tbl_ai_shop_profile
              (shop_id, cuisine, scene_tags, ambience_tags, queue_level, summary,
               input_revision, aggregated_revision, profile_status)
            VALUES (#{shopId}, '', '', '', 'UNKNOWN', '', 1, 0, 'WAIT_REBUILD')
            ON DUPLICATE KEY UPDATE
              input_revision = input_revision + 1,
              profile_status = 'WAIT_REBUILD'
            """)
    int markDirty(@Param("shopId") Long shopId);

    @Update("""
            UPDATE tbl_ai_shop_profile
            SET scene_tags = #{sceneTags},
                ambience_tags = #{ambienceTags},
                queue_level = #{queueLevel},
                summary = #{summary},
                aggregated_revision = #{expectedRevision},
                profile_status = 'READY'
            WHERE shop_id = #{shopId}
              AND profile_status = 'WAIT_REBUILD'
              AND input_revision = #{expectedRevision}
            """)
    int completeRebuild(@Param("shopId") Long shopId,
                        @Param("expectedRevision") Long expectedRevision,
                        @Param("sceneTags") String sceneTags,
                        @Param("ambienceTags") String ambienceTags,
                        @Param("queueLevel") String queueLevel,
                        @Param("summary") String summary);

    @Update("""
            UPDATE tbl_ai_shop_profile
            SET aggregated_revision = #{expectedRevision},
                profile_status = 'READY'
            WHERE shop_id = #{shopId}
              AND profile_status = 'WAIT_REBUILD'
              AND input_revision = #{expectedRevision}
            """)
    int completeWithoutReviews(@Param("shopId") Long shopId,
                               @Param("expectedRevision") Long expectedRevision);

    /** Layer 6 data-driven critique anchor: average price of shops matching cuisine prefix (e.g. "火锅" matches "火锅,重庆").
     *  Used as AVG*0.8 default budget band when candidate pool is empty (opening critique).
     *  Returns null when no matching shop has a price. */
    @Select("SELECT ROUND(AVG(s.avg_price), 0) FROM tbl_ai_shop_profile p JOIN tbl_shop s ON p.shop_id = s.id " +
            "WHERE p.cuisine LIKE CONCAT(#{cuisine}, '%') AND s.avg_price > 0")
    Integer selectAvgPriceByCuisine(@Param("cuisine") String cuisine);
}
