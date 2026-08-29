package com.hmdp.ai.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.DropCollectionParam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in verification that the pinned SDK can inspect a Spring AI Milvus collection without writing through the inspector. */
@EnabledIfSystemProperty(named = "milvus.integration.enabled", matches = "true")
class MilvusVectorIndexInspectorIntegrationTest {
    private MilvusServiceClient client;
    private String collection;

    @AfterEach
    void cleanup() throws Exception {
        if (client == null) return;
        if (collection != null) client.dropCollection(DropCollectionParam.newBuilder().withCollectionName(collection).build());
        client.close(1);
    }

    @Test
    void preflightAndScanReadStableIdsAndJsonMetadata() throws Exception {
        client = new MilvusServiceClient(ConnectParam.newBuilder().withHost("127.0.0.1").withPort(19530).build());
        collection = "codex_vector_inspect_" + UUID.randomUUID().toString().replace("-", "");
        MilvusVectorStore store = MilvusVectorStore.builder(client, new FixedEmbeddingModel())
                .collectionName(collection).embeddingDimension(3).autoId(false).initializeSchema(true).build();
        store.afterPropertiesSet();
        store.add(List.of(
                new Document("shop-profile-7", "profile", Map.of("shopId", 7L, "documentType", "PROFILE", "profileRevision", 11L)),
                new Document("shop-review-31", "review", Map.of("shopId", 7L, "documentType", "REVIEW", "sourceRevision", 4L))));

        MilvusVectorIndexInspector inspector = new MilvusVectorIndexInspector(client, collection, 3);

        assertTrue(inspector.preflight().compatible());
        List<MilvusVectorIndexInspector.IndexedDocument> documents = inspector.scanDocuments();
        assertEquals(2, documents.size());
        assertTrue(documents.stream().anyMatch(document -> document.documentId().equals("shop-profile-7")
                && "PROFILE".equals(document.metadata().get("documentType"))));
        assertTrue(documents.stream().anyMatch(document -> document.documentId().equals("shop-review-31")
                && ((Number) document.metadata().get("sourceRevision")).longValue() == 4L));
    }

    private static final class FixedEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> results = new ArrayList<>();
            for (int index = 0; index < request.getInstructions().size(); index++) results.add(new Embedding(new float[] {1F, 0F, 0F}, index));
            return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(Document document) { return new float[] {1F, 0F, 0F}; }

        @Override
        public int dimensions() { return 3; }
    }
}
