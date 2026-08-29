package com.hmdp.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.ai.entity.AiShopProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

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
}
