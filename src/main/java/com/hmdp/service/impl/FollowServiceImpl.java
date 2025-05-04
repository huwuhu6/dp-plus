package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    /**
     * 查询当前用户是否关注
     * @param id
     * @return
     */
    public Result isFollow(Long id) {
        //1.获取当前用户
        Long userId= UserHolder.getUser().getId();
        Integer count=query().eq("user_id",userId).eq("follow_id",id).count();
        return Result.ok(count>0);
    }
}
