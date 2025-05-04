package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
        Integer count=query().eq("user_id",userId).eq("follow_user_id",id).count();
        return Result.ok(count>0);
    }

    /**
     * 关注或者取关
     * @param id
     * @param isFollw true为关注
     * @return
     */
    public Result follow(Long id, Boolean isFollw) {
        //1.获取用户id
        Long userId= UserHolder.getUser().getId();
        //2.关注
        if(isFollw){
            //新增一个关注的表
            Follow follow=new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            save(follow);
        }else{
            remove(new QueryWrapper<Follow>().eq("user_id",userId).eq("follow_user_id",id));
        }
        return Result.ok();
    }
}
