package com.hmdp.ai.controller;

import com.hmdp.ai.dto.LocalDemoDatasetRequest;
import com.hmdp.ai.dto.LocalDemoDatasetResponse;
import com.hmdp.ai.service.LocalDemoDatasetService;
import com.hmdp.dto.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/local-demo-dataset")
public class AiLocalDemoDatasetController {
    @Resource private LocalDemoDatasetService localDemoDatasetService;

    @PostMapping("/generate")
    public Result generate(@RequestBody LocalDemoDatasetRequest request) {
        LocalDemoDatasetResponse response = localDemoDatasetService.generate(request);
        return Result.ok(response);
    }

    @PostMapping("/purge")
    public Result purge(@RequestParam String city) {
        return Result.ok(java.util.Collections.singletonMap("deletedShops", localDemoDatasetService.purgeGeneratedCity(city)));
    }
}
