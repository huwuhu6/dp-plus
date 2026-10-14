package com.hmdp.ai.v2;

import com.hmdp.ai.v2.runtime.SemanticInterpretationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MissingCuisineExtractionTest {
    final SemanticRepairTest h=new SemanticRepairTest();
    String first="{\"criteria\":{},"+h.request+"}";
    @Test void completeFirstNeedsNoExtraction() {
        h.replies("{\"criteria\":{\"cuisineAnyOf\":[\"日料\",\"川菜\"]},"+h.request+"}","{}");
        assertEquals(List.of("日料","川菜"),h.patch(h.parser.interpret("找日料或者川菜")).cuisine().anyOf());
        verify(h.client,times(1)).chatCompletion(anyList(),anyList(),anyMap(),anyString());
    }
    @Test void missingOrUsesIndependentSmallSchemaAndKeepsOr() {
        h.replies(first,"{\"cuisine\":null,\"cuisineAnyOf\":[\"日料\",\"川菜\"],\"excludedCuisines\":[]}");
        var cuisine=h.patch(h.parser.interpret("找日料或者川菜")).cuisine();
        assertNull(cuisine.include());assertEquals(List.of("日料","川菜"),cuisine.anyOf());
        var tools=ArgumentCaptor.forClass(List.class);verify(h.client,times(2)).chatCompletion(anyList(),tools.capture(),anyMap(),anyString());
        String schema=tools.getAllValues().getLast().toString();
        assertTrue(schema.contains("extract_missing_dining_criteria"));assertFalse(schema.contains("taskDirective"));assertFalse(schema.contains("requests"));
        assertEquals("TARGETED_EXTRACTION",h.parser.takeEvaluationAttempts().getLast().get("attempt"));
    }
    @Test void singleCuisineCanBeExtracted() {
        h.replies(first,"{\"cuisine\":\"日料\",\"cuisineAnyOf\":[],\"excludedCuisines\":[]}");
        assertEquals("日料",h.patch(h.parser.interpret("找日料")).cuisine().include());
    }
    @Test void inventedCuisineAndIncompleteOrAreRejected() {
        for(String candidate:List.of("{\"cuisine\":\"粤菜\",\"cuisineAnyOf\":[],\"excludedCuisines\":[]}",
                "{\"cuisine\":\"日料\",\"cuisineAnyOf\":[],\"excludedCuisines\":[]}",
                "{\"cuisine\":null,\"cuisineAnyOf\":[],\"excludedCuisines\":[]}")) {
            reset(h.client);h.replies(first,candidate);
            assertThrows(SemanticInterpretationException.class,()->h.parser.interpret("找日料或者川菜"));
            assertNotEquals("VALID",h.parser.takeEvaluationAttempts().getLast().get("validationResult"));
        }
    }
    @Test void oldFeedbackCuisineCannotBecomeNewArrangementCuisine() {
        h.replies("{\"taskDirective\":\"START_NEW\",\"taskDirectiveEvidence\":\"EXPLICIT_NEW_TASK\",\"criteria\":{},\"feedback\":[{\"target\":{\"ordinal\":2},\"kind\":\"POSITIVE\"}],"+h.request+"}",
                "{\"cuisine\":\"面\",\"cuisineAnyOf\":[],\"excludedCuisines\":[]}");
        var error=assertThrows(SemanticInterpretationException.class,()->h.parser.interpret("第二家面馆不错。对了，另外找日料或者川菜"));
        assertTrue(error.getCause().getMessage().contains("unsupported extraction source"));
    }
    @Test void extractionCannotRewriteNonTargetDimensionsEvenIfUserSupportsThem() {
        h.replies(first,"{\"cuisine\":\"日料\",\"cuisineAnyOf\":[],\"excludedCuisines\":[],\"poi\":\"大学城\"}");
        var error=assertThrows(SemanticInterpretationException.class,()->h.parser.interpret("大学城附近找日料"));
        assertTrue(error.getCause().getMessage().contains("cannot update criteria.poi"));
    }
}
