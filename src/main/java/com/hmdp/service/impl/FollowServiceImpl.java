package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private IUserService userService;
    /**
     * 查询当前用户是否关注
     * @param id
     * @return
     */
    public Result isFollow(Long id) {
        //1.获取当前用户
        Long userId= UserHolder.getUser().getId();
        Long count=query().eq("user_id",userId).eq("follow_user_id",id).count();
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
        String key="follow:"+userId;
        //2.关注
        if(isFollw){
            //新增一个关注的表
            Follow follow=new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean isSuccess=save(follow);
            if(isSuccess){
                redisTemplate.opsForSet().add(key,id.toString());
            }
        }else{
            remove(new QueryWrapper<Follow>().eq("user_id",userId).eq("follow_user_id",id));
            redisTemplate.opsForSet().remove(key,id.toString());
        }
        return Result.ok();
    }

    /**
     * 查看共同关注
     * @param id 查看的对方主页的用户id
     * @return
     */
    public Result followCommons(Long id) {
        //1.获取当前用户id
        Long userId= UserHolder.getUser().getId();
        //2.求交集
        Set<String> intersect=redisTemplate.opsForSet().intersect("follow:"+userId,"follow:"+id);
        if(intersect==null||intersect.size()==0){
            return Result.ok();
        }
        //3.解析ids
        List<Long> ids=intersect
                .stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        //4.返回user
        List<UserDTO>userDTOS=userService.listByIds(ids)
                .stream()
                .map(user-> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
