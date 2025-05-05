package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {
    /**
     * 查询当前用户是否关注
     * @param id
     * @return
     */
    Result isFollow(Long id);

    /**
     * 关注或者取关
     * @param id
     * @param isFollw
     * @return
     */
    Result follow(Long id, Boolean isFollw);

    /**
     * 查看共同关注
     * @param id
     * @return
     */
    Result followCommons(Long id);
}
