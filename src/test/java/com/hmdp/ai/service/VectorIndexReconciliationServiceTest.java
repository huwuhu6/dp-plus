package com.hmdp.ai.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VectorIndexReconciliationServiceTest {
    @Test
    void missingProfileOnlyRequeuesExistingVectorSyncIntent() {
        VectorIndexReconciliationService service = new VectorIndexReconciliationService();
        SemanticShopDocumentSource source = mock(SemanticShopDocumentSource.class);
        MilvusVectorIndexInspector inspector = mock(MilvusVectorIndexInspector.class);
        VectorSyncTaskService taskService = mock(VectorSyncTaskService.class);
        ReflectionTestUtils.setField(service, "documentSource", source);
        ReflectionTestUtils.setField(service, "inspector", inspector);
        ReflectionTestUtils.setField(service, "taskService", taskService);
        Document document = new Document("shop-profile-7", "profile", Map.of("documentFingerprint", "fingerprint"));
        when(inspector.preflight()).thenReturn(new MilvusVectorIndexInspector.SchemaPreflight("test", true, List.of()));
        when(inspector.scanDocuments()).thenReturn(List.of());
        when(source.loadSnapshot()).thenReturn(new SemanticShopDocumentSource.Snapshot(1,
                List.of(new SemanticShopDocumentSource.ExpectedDocument(document, SemanticShopDocumentFactory.PROFILE, 7L, 7L, 11L))));

        VectorIndexReconciliationService.ReconciliationReport report = service.reconcile();

        assertTrue(report.schemaCompatible());
        assertEquals(1, report.drifts().size());
        assertEquals(VectorIndexReconciliationService.Kind.MISSING, report.drifts().get(0).kind());
        assertEquals(1, report.repairRequests());
        verify(taskService).requestProfileRepair(7L, 11L);
    }

    @Test
    void schemaFailureDoesNotRequestRepair() {
        VectorIndexReconciliationService service = new VectorIndexReconciliationService();
        SemanticShopDocumentSource source = mock(SemanticShopDocumentSource.class);
        MilvusVectorIndexInspector inspector = mock(MilvusVectorIndexInspector.class);
        VectorSyncTaskService taskService = mock(VectorSyncTaskService.class);
        ReflectionTestUtils.setField(service, "documentSource", source);
        ReflectionTestUtils.setField(service, "inspector", inspector);
        ReflectionTestUtils.setField(service, "taskService", taskService);
        when(inspector.preflight()).thenReturn(new MilvusVectorIndexInspector.SchemaPreflight("test", false, List.of("missing metadata")));

        VectorIndexReconciliationService.ReconciliationReport report = service.reconcile();

        assertFalse(report.schemaCompatible());
        assertEquals(0, report.repairRequests());
        verifyNoInteractions(source, taskService);
    }
}
