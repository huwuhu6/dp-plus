package com.hmdp.ai.controller;

import com.hmdp.ai.service.SemanticShopRetriever;
import com.hmdp.dto.Result;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

@RestController
@RequestMapping("/ai/retrieval")
@ConditionalOnProperty(prefix = "ai.retrieval", name = "vector-enabled", havingValue = "true")
public class AiRetrievalController {
    @Resource private SemanticShopRetriever semanticShopRetriever;

    @PostMapping("/indexes/rebuild")
    public Result rebuildIndex() {
        int documentCount = semanticShopRetriever.rebuildIndex();
        return Result.ok(Collections.singletonMap("documentCount", documentCount));
    }
}
