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
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in verification of the pinned Spring AI 1.0.3 adapter against a local disposable collection. */
@EnabledIfSystemProperty(named = "milvus.integration.enabled", matches = "true")
class MilvusVectorStoreDuplicateIdIntegrationTest {
    private MilvusServiceClient client;
    private String collection;

    @AfterEach
    void cleanup() throws Exception {
        if (client == null) return;
        if (collection != null) client.dropCollection(DropCollectionParam.newBuilder().withCollectionName(collection).build());
        client.close(1);
    }

    @Test
    void addDoesNotProvideSafeReplacementForTheSameDocumentId() throws Exception {
        client = new MilvusServiceClient(ConnectParam.newBuilder().withHost("127.0.0.1").withPort(19530).build());
        collection = "codex_vector_id_" + UUID.randomUUID().toString().replace("-", "");
        MilvusVectorStore store = MilvusVectorStore.builder(client, new FixedEmbeddingModel())
                .collectionName(collection).embeddingDimension(3).autoId(false).initializeSchema(true).build();
        store.afterPropertiesSet();

        store.add(List.of(new Document("same-id", "old", Map.of("shopId", 1L))));
        store.add(List.of(new Document("same-id", "new", Map.of("shopId", 1L))));
        List<Document> matches = store.similaritySearch(SearchRequest.builder().query("new").topK(10).build());

        assertTrue(matches.size() >= 1);
        assertEquals("new", matches.get(0).getText());
    }

    private static final class FixedEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> results = new ArrayList<>();
            for (int index = 0; index < request.getInstructions().size(); index++) {
                String value = request.getInstructions().get(index);
                results.add(new Embedding(value.contains("new") ? new float[] {0F, 1F, 0F} : new float[] {1F, 0F, 0F}, index));
            }
            return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(Document document) {
            return document.getText().contains("new") ? new float[] {0F, 1F, 0F} : new float[] {1F, 0F, 0F};
        }

        @Override
        public int dimensions() { return 3; }
    }
}
