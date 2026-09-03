package com.hmdp.ai.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuisineCanonicalizerTest {

    // --- Japanese ---

    @Test
    void 日料映射到日料() {
        assertEquals("日料", CuisineCanonicalizer.canonicalize("日料"));
    }

    @Test
    void 日本料理映射到日料() {
        assertEquals("日料", CuisineCanonicalizer.canonicalize("日本料理"));
    }

    @Test
    void 日式料理映射到日料() {
        assertEquals("日料", CuisineCanonicalizer.canonicalize("日式料理"));
    }

    @Test
    void 日本菜映射到日料() {
        assertEquals("日料", CuisineCanonicalizer.canonicalize("日本菜"));
    }

    @Test
    void 寿司映射到日料() {
        assertEquals("日料", CuisineCanonicalizer.canonicalize("寿司"));
    }

    // --- BBQ ---

    @Test
    void 烧烤映射到烧烤() {
        assertEquals("烧烤", CuisineCanonicalizer.canonicalize("烧烤"));
    }

    @Test
    void 烤肉映射到烧烤() {
        assertEquals("烧烤", CuisineCanonicalizer.canonicalize("烤肉"));
    }

    // --- Western ---

    @Test
    void 西餐映射到西餐() {
        assertEquals("西餐", CuisineCanonicalizer.canonicalize("西餐"));
    }

    @Test
    void 牛排映射到西餐() {
        assertEquals("西餐", CuisineCanonicalizer.canonicalize("牛排"));
    }

    // --- Hong Kong style ---

    @Test
    void 港式映射到港式() {
        assertEquals("港式", CuisineCanonicalizer.canonicalize("港式"));
    }

    @Test
    void 茶餐厅映射到港式() {
        assertEquals("港式", CuisineCanonicalizer.canonicalize("茶餐厅"));
    }

    @Test
    void 港式茶餐厅映射到港式() {
        assertEquals("港式", CuisineCanonicalizer.canonicalize("港式茶餐厅"));
    }

    // --- Hot pot ---

    @Test
    void 火锅映射到火锅() {
        assertEquals("火锅", CuisineCanonicalizer.canonicalize("火锅"));
    }

    // --- Unmapped ---

    @Test
    void 未知菜系保持原样() {
        assertEquals("川菜", CuisineCanonicalizer.canonicalize("川菜"));
    }

    @Test
    void 湘菜保持原样() {
        assertEquals("湘菜", CuisineCanonicalizer.canonicalize("湘菜"));
    }

    @Test
    void 粤菜保持原样() {
        assertEquals("粤菜", CuisineCanonicalizer.canonicalize("粤菜"));
    }

    // --- Edge cases ---

    @Test
    void null返回空字符串() {
        assertEquals("", CuisineCanonicalizer.canonicalize(null));
    }

    @Test
    void 空字符串返回空字符串() {
        assertEquals("", CuisineCanonicalizer.canonicalize(""));
    }

    @Test
    void 空白字符串返回空字符串() {
        assertEquals("", CuisineCanonicalizer.canonicalize("   "));
    }

    @Test
    void 前后空格被裁剪() {
        assertEquals("日料", CuisineCanonicalizer.canonicalize("  日料  "));
    }

    // --- Idempotency ---

    @Test
    void canonicalize幂等性() {
        String once = CuisineCanonicalizer.canonicalize("日式料理");
        String twice = CuisineCanonicalizer.canonicalize(once);
        assertEquals("日料", once);
        assertEquals(once, twice);
    }

    // --- knownCanonicalValues ---

    @Test
    void 已知canonical值包含所有预期值() {
        java.util.Set<String> values = CuisineCanonicalizer.knownCanonicalValues();
        assertTrue(values.contains("日料"));
        assertTrue(values.contains("烧烤"));
        assertTrue(values.contains("西餐"));
        assertTrue(values.contains("港式"));
        assertTrue(values.contains("火锅"));
        assertTrue(values.contains("川菜"));
        assertTrue(values.contains("湘菜"));
        assertTrue(values.contains("快餐简餐"));
        assertTrue(values.contains("面食"));
        assertTrue(values.contains("小吃"));
        assertTrue(values.contains("咖啡"));
        assertTrue(values.contains("其他"));
        assertTrue(values.size() >= 20);
    }
}