package com.hmdp.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultCompressorTest {
    @Test
    void truncatesLongEvidenceAndLargeFactLists() {
        ToolResultCompressor compressor = new ToolResultCompressor();
        ReflectionTestUtils.setField(compressor, "objectMapper", new ObjectMapper());
        AgentToolResult result = new AgentToolResult().summary(repeat('s', 300)).displayText(repeat('d', 2000));
        ArrayList<String> evidence = new ArrayList<String>();
        for (int index = 0; index < 10; index++) evidence.add(repeat('e', 300));
        result.getFacts().put("evidence", evidence);

        compressor.compress(result);

        assertTrue(result.getSummary().length() <= 240);
        assertTrue(result.getDisplayText().length() <= 1600);
        assertEquals(6, ((java.util.List<?>) result.getFacts().get("evidence")).size());
        assertTrue(((String) ((java.util.List<?>) result.getFacts().get("evidence")).get(0)).length() <= 240);
    }

    private String repeat(char value, int count) {
        return String.valueOf(value).repeat(count);
    }
}
