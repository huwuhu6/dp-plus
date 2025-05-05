package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;
    /**
     * 查询当前用户是否关注
     * @param id
     * @return
     */

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable(value = "id") Long id){
        return followService.isFollow(id);
    }

    /**
     * 关注或者取关
     * @param id
     * @param isFollw
     * @return
     */
    @PutMapping("/{id}/{isFollw}")
    public Result Follow(@PathVariable(value = "id")Long id, @PathVariable(value = "isFollw")Boolean isFollw){
        return followService.follow(id,isFollw);
    }

    /**
     * 查看共同关注
     * @param id
     * @return
     */
     @GetMapping("/common/{id}")
     public Result FollowCommons(@PathVariable(value = "id")Long id){
        return followService.followCommons(id);
    }
}
