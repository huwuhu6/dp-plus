package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;
    @Resource
    private RedisTemplate redisTemplate;
    /**
     * 分页查询blog数据
     * @param current
     * @return
     */
    public Object queryHotBloyByID(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户,并赋值blog对应的用户信息
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return records;
    }

    /**
     * 判断当前用户是否点赞
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        //1.判断用户是否已经点赞
        //1.1获取用户
        Long userId= UserHolder.getUser().getId();
        if (userId==null){
            return;
        }
        String key="blog:liked:"+blog.getId();
        //1.2判断用户是否在集合中
        Double isMember=redisTemplate.opsForZSet().score(key,userId.toString());
        blog.setIsLike(isMember!=null);
    }

    /**
     * 查询blog详细信息
     * @param id
     * @return
     */
    @Override
    public Object queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return "博客信息不存在";
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return blog;
    }

    /**
     * 用户点赞
     * @param id blogId
     */
    @Override
    public Result likeBlog(Long id) {
        //1.判断用户是否已经点赞
        //1.1获取用户
        Long userId= UserHolder.getUser().getId();
        if (userId==null){
            return Result.ok();
        }
        String key="blog:liked:"+id;
        //1.2判断用户是否在集合中
        Double isMember=redisTemplate.opsForZSet().score(key,userId.toString());
        //2 在集合中
        if(isMember!=null){
            //2.1数据库减1
            Boolean success= update().setSql("liked=liked-1").eq("id",id).update();
            //2.2从redis中删除
            if (success) {
                redisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }else{
            //3.不在集合中
            update().setSql("liked=liked+1").eq("id",id).update();
            redisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
        }
        return Result.ok();
    }

    /**
     * 根据博客找到对应的用户信息
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
