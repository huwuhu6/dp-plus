package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiConversationEvaluationCase;
import com.hmdp.ai.entity.dto.AiConversationEvaluationCaseDto;
import com.hmdp.ai.mapper.AiConversationEvaluationCaseMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话评测用例加载器：优先从 Git 版本化的 JSONL 资源文件加载，
 * 资源不存在时降级到 MySQL 查询，保证向后兼容。
 *
 * JSONL 路径：src/main/resources/eval/datasets/{datasetVersion}.jsonl
 */
@Component
public class ConversationEvaluationDatasetLoader {

    private static final Logger log = LoggerFactory.getLogger(ConversationEvaluationDatasetLoader.class);
    private static final String DATASET_PREFIX = "eval/datasets/";
    private static final String DATASET_SUFFIX = ".jsonl";

    @Resource private ObjectMapper objectMapper;
    @Resource private AiConversationEvaluationCaseMapper caseMapper;

    /**
     * 加载指定数据集版本的用例。
     * 优先从 classpath JSONL 加载；文件不存在时降级到 MySQL。
     * 返回的用例已过滤 active=true 并按虚拟 ID 升序排序。
     */
    public List<AiConversationEvaluationCase> loadCases(String datasetVersion) {
        List<AiConversationEvaluationCase> fromFile = loadFromClasspath(datasetVersion);
        if (fromFile != null) {
            log.info("[AI][eval] dataset={} loaded from JSONL, {} cases", datasetVersion, fromFile.size());
            return fromFile;
        }
        log.info("[AI][eval] dataset={} JSONL not found, fallback to MySQL", datasetVersion);
        return caseMapper.selectList(new QueryWrapper<AiConversationEvaluationCase>()
                .eq("active", true).eq("dataset_version", datasetVersion).orderByAsc("id"));
    }

    /**
     * 从 classpath 加载 JSONL，文件不存在返回 null。
     */
    private List<AiConversationEvaluationCase> loadFromClasspath(String datasetVersion) {
        String path = DATASET_PREFIX + datasetVersion + DATASET_SUFFIX;
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        List<AiConversationEvaluationCase> cases = new ArrayList<>();
        try (InputStream is = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            int index = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("//")) continue;
                AiConversationEvaluationCaseDto dto = objectMapper.readValue(line, AiConversationEvaluationCaseDto.class);
                if (dto.getActive() == null || !dto.getActive()) continue;
                cases.add(convert(dto, ++index));
            }
        } catch (Exception e) {
            log.error("[AI][eval] failed to load JSONL dataset={}: {}", datasetVersion, e.getMessage(), e);
            throw new IllegalStateException("加载评测数据集失败: " + datasetVersion, e);
        }
        return cases;
    }

    /**
     * DTO → 实体转换：复杂结构序列化为 JSON 字符串，赋虚拟 ID。
     */
    private AiConversationEvaluationCase convert(AiConversationEvaluationCaseDto dto, long virtualId) {
        AiConversationEvaluationCase c = new AiConversationEvaluationCase();
        c.setId(virtualId);
        c.setCaseCode(dto.getCaseCode());
        c.setDatasetVersion(dto.getDatasetVersion());
        c.setActive(dto.getActive());
        c.setTurnsJson(writeJson(dto.getTurns()));
        c.setExpectedRoutesJson(writeJson(dto.getExpectedRoutes()));
        c.setExpectedContextRewritesJson(writeJson(dto.getExpectedContextRewrites()));
        c.setExpectedToolNamesJson(writeJson(dto.getExpectedToolNames()));
        c.setExpectedToolArgumentsJson(writeJson(dto.getExpectedToolArguments()));
        c.setExpectedFinalStatus(dto.getExpectedFinalStatus());
        c.setExpectedShopIds(dto.getExpectedShopIds());
        c.setExpectedCity(dto.getExpectedCity());
        c.setExpectedErrorCount(dto.getExpectedErrorCount());
        c.setExpectedRecoveryRoutesJson(writeJson(dto.getExpectedRecoveryRoutes()));
        c.setExpectedMemoryJson(dto.getExpectedMemory() != null ? writeJson(dto.getExpectedMemory()) : null);
        c.setExpectedUnseenFromTurn(dto.getExpectedUnseenFromTurn());
        c.setExpectedUnseenPairsJson(writeJson(dto.getExpectedUnseenPairs()));
        c.setExpectedTurnStatesJson(writeJson(dto.getExpectedTurnStates()));
        c.setExpectedToolsByTurnJson(writeJson(dto.getExpectedToolsByTurn()));
        c.setExpectedRelationsJson(writeJson(dto.getExpectedRelations()));
        c.setNotes(dto.getNotes());
        return c;
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
