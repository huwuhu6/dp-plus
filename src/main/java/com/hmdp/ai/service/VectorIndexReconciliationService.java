package com.hmdp.ai.service;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Finds logical Milvus drift and only re-creates durable VectorSyncTask intent; it never writes Milvus directly. */
@Service
public class VectorIndexReconciliationService {
    @Resource private SemanticShopDocumentSource documentSource;
    @Resource private MilvusVectorIndexInspector inspector;
    @Resource private VectorSyncTaskService taskService;

    public ReconciliationReport inspect() {
        MilvusVectorIndexInspector.SchemaPreflight preflight = inspector.preflight();
        if (!preflight.compatible()) return ReconciliationReport.schemaFailure(preflight);
        SemanticShopDocumentSource.Snapshot snapshot = documentSource.loadSnapshot();
        Map<String, SemanticShopDocumentSource.ExpectedDocument> expected = new HashMap<>();
        for (var document : snapshot.documents()) expected.put(document.document().getId(), document);
        Map<String, MilvusVectorIndexInspector.IndexedDocument> actual = new HashMap<>();
        for (var document : inspector.scanDocuments()) actual.put(document.documentId(), document);

        List<Drift> drifts = new ArrayList<>();
        for (var entry : expected.entrySet()) {
            MilvusVectorIndexInspector.IndexedDocument indexed = actual.remove(entry.getKey());
            if (indexed == null) drifts.add(Drift.missing(entry.getValue()));
            else if (revision(indexed.metadata(), entry.getValue().documentType()) != entry.getValue().revision()) drifts.add(Drift.stale(entry.getValue(), indexed));
            else if (!entry.getValue().document().getMetadata().get("documentFingerprint").equals(indexed.metadata().get("documentFingerprint"))) drifts.add(Drift.fingerprint(entry.getValue(), indexed));
        }
        for (var indexed : actual.values()) {
            ManagedIdentity identity = managedIdentity(indexed);
            if (identity != null) drifts.add(Drift.orphan(identity, indexed));
        }
        return new ReconciliationReport(preflight, List.copyOf(drifts), 0);
    }

    public ReconciliationReport reconcile() {
        ReconciliationReport report = inspect();
        if (!report.schemaCompatible()) return report;
        int requested = 0;
        for (Drift drift : report.drifts()) {
            if (drift.expected() != null) {
                var expected = drift.expected();
                if (SemanticShopDocumentFactory.PROFILE.equals(expected.documentType())) taskService.requestProfileRepair(expected.shopId(), expected.revision());
                else taskService.requestReviewRepair(expected.entityId(), expected.shopId(), expected.revision());
                requested++;
            } else if (drift.identity() != null && !containsCurrentExpected(drift.indexed().documentId())) {
                var identity = drift.identity();
                taskService.requestManagedDeleteRepair(drift.indexed().documentId(), identity.documentType(), identity.entityId(), identity.shopId(), identity.revision());
                requested++;
            }
        }
        return new ReconciliationReport(report.preflight(), report.drifts(), requested);
    }

    private boolean containsCurrentExpected(String documentId) {
        return documentSource.loadSnapshot().documents().stream().anyMatch(document -> document.document().getId().equals(documentId));
    }

    private long revision(Map<String, Object> metadata, String type) {
        Object value = metadata.get(SemanticShopDocumentFactory.PROFILE.equals(type) ? "profileRevision" : "sourceRevision");
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private ManagedIdentity managedIdentity(MilvusVectorIndexInspector.IndexedDocument document) {
        Object type = document.metadata().get("documentType");
        Object shop = document.metadata().get("shopId");
        if (!(shop instanceof Number number)) return null;
        long shopId = number.longValue();
        if (SemanticShopDocumentFactory.PROFILE.equals(type) && document.documentId().startsWith("shop-profile-")) {
            Long entityId = suffix(document.documentId(), "shop-profile-");
            return entityId == null || entityId != shopId ? null : new ManagedIdentity(String.valueOf(type), entityId, shopId, revision(document.metadata(), String.valueOf(type)));
        }
        if (SemanticShopDocumentFactory.REVIEW.equals(type) && document.documentId().startsWith("shop-review-")) {
            Long entityId = suffix(document.documentId(), "shop-review-");
            return entityId == null ? null : new ManagedIdentity(String.valueOf(type), entityId, shopId, revision(document.metadata(), String.valueOf(type)));
        }
        return null;
    }

    private Long suffix(String id, String prefix) {
        try { return Long.parseLong(id.substring(prefix.length())); } catch (RuntimeException ignored) { return null; }
    }

    public record ReconciliationReport(MilvusVectorIndexInspector.SchemaPreflight preflight, List<Drift> drifts, int repairRequests) {
        static ReconciliationReport schemaFailure(MilvusVectorIndexInspector.SchemaPreflight preflight) { return new ReconciliationReport(preflight, List.of(), 0); }
        public boolean schemaCompatible() { return preflight.compatible(); }
    }
    public record Drift(Kind kind, SemanticShopDocumentSource.ExpectedDocument expected,
                        MilvusVectorIndexInspector.IndexedDocument indexed, ManagedIdentity identity) {
        static Drift missing(SemanticShopDocumentSource.ExpectedDocument expected) { return new Drift(Kind.MISSING, expected, null, null); }
        static Drift stale(SemanticShopDocumentSource.ExpectedDocument expected, MilvusVectorIndexInspector.IndexedDocument indexed) { return new Drift(Kind.STALE_REVISION, expected, indexed, null); }
        static Drift fingerprint(SemanticShopDocumentSource.ExpectedDocument expected, MilvusVectorIndexInspector.IndexedDocument indexed) { return new Drift(Kind.FINGERPRINT_MISMATCH, expected, indexed, null); }
        static Drift orphan(ManagedIdentity identity, MilvusVectorIndexInspector.IndexedDocument indexed) { return new Drift(Kind.ORPHAN, null, indexed, identity); }
    }
    public enum Kind { MISSING, STALE_REVISION, FINGERPRINT_MISMATCH, ORPHAN }
    public record ManagedIdentity(String documentType, Long entityId, Long shopId, long revision) { }
}
