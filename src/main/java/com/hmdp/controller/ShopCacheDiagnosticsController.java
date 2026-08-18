package com.hmdp.controller;

import com.hmdp.cache.ShopLocalCache;
import com.hmdp.dto.Result;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Only available in the local development profile for benchmark evidence. */
@Profile("dev")
@RestController
@RequestMapping("/internal/cache/shop")
public class ShopCacheDiagnosticsController {
    @Resource private ShopLocalCache shopLocalCache;

    @GetMapping("/stats")
    public Result stats() {
        return Result.ok(shopLocalCache.stats());
    }
}
