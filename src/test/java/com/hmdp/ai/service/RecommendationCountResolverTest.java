package com.hmdp.ai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecommendationCountResolverTest {
    private final RecommendationCountResolver resolver = new RecommendationCountResolver();

    @Test
    void keepsTheDefaultWhenTheUserDoesNotRequestACount() {
        assertEquals(3, resolver.resolve("帮我找适合聚餐的餐厅"));
        assertEquals(3, resolver.resolve("换几家看看"));
    }

    @Test
    void resolvesExplicitArabicAndChineseCountsWithinTheSupportedRange() {
        assertEquals(4, resolver.resolve("给我推荐4家火锅店"));
        assertEquals(2, resolver.resolve("再来两家安静的"));
        assertEquals(5, resolver.resolve("推荐10家不同的餐厅"));
    }

    @Test
    void treatsMoreSeveralShopsAsTheSupportedMaximum() {
        assertEquals(5, resolver.resolve("多推荐几家不同的"));
        assertEquals(5, resolver.resolve("再多来几家"));
    }
}
