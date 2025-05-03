package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Object queryHotBloyByID(Integer current);

    Object queryBlogById(Long id);

    /**
     * 用户点赞
     * @param id
     */
    Result likeBlog(Long id);
}
