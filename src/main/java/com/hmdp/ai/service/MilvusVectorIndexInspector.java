package com.hmdp.ai.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.FieldSchema;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.param.R;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.dml.QueryIteratorParam;
import io.milvus.response.QueryResultsWrapper;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only native Milvus adapter used by future reconciliation; it never writes or repairs vectors. */
@Service
public class MilvusVectorIndexInspector {
    private static final String ID_FIELD = "doc_id";
    private static final String CONTENT_FIELD = "content";
    private static final String METADATA_FIELD = "metadata";
    private static final String EMBEDDING_FIELD = "embedding";

    @Autowired(required = false) private VectorStore vectorStore;
    @Value("${spring.ai.vectorstore.milvus.collection-name:vector_store}") private String configuredCollectionName;
    @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1536}") private int configuredEmbeddingDimension;

    private final MilvusServiceClient suppliedClient;
    private final String suppliedCollectionName;
    private final Integer suppliedEmbeddingDimension;

    public MilvusVectorIndexInspector() {
        this.suppliedClient = null;
        this.suppliedCollectionName = null;
        this.suppliedEmbeddingDimension = null;
    }

    MilvusVectorIndexInspector(MilvusServiceClient client, String collectionName, int embeddingDimension) {
        this.suppliedClient = client;
        this.suppliedCollectionName = collectionName;
        this.suppliedEmbeddingDimension = embeddingDimension;
    }

    public SchemaPreflight preflight() {
        R<io.milvus.grpc.DescribeCollectionResponse> response = client().describeCollection(
                DescribeCollectionParam.newBuilder().withCollectionName(collectionName()).build());
        if (response.getException() != null) {
            return new SchemaPreflight(collectionName(), false, List.of("describe failed: " + response.getException().getClass().getSimpleName()));
        }
        List<String> violations = new ArrayList<>();
        Map<String, FieldSchema> fields = response.getData().getSchema().getFieldsList().stream()
                .collect(java.util.stream.Collectors.toMap(FieldSchema::getName, field -> field));
        checkField(fields, ID_FIELD, DataType.VarChar, true, violations);
        checkField(fields, CONTENT_FIELD, DataType.VarChar, false, violations);
        checkField(fields, METADATA_FIELD, DataType.JSON, false, violations);
        checkField(fields, EMBEDDING_FIELD, DataType.FloatVector, false, violations);
        FieldSchema embedding = fields.get(EMBEDDING_FIELD);
        if (embedding != null && dimension(embedding) != embeddingDimension()) {
            violations.add("embedding dimension expected=" + embeddingDimension() + " actual=" + dimension(embedding));
        }
        if (response.getData().getSchema().getAutoID()) violations.add("collection autoId must be false");
        return new SchemaPreflight(collectionName(), violations.isEmpty(), List.copyOf(violations));
    }

    public List<IndexedDocument> scanDocuments() {
        SchemaPreflight preflight = preflight();
        if (!preflight.compatible()) throw new IllegalStateException("Milvus schema incompatible: " + preflight.violations());
        R<QueryIterator> response = client().queryIterator(QueryIteratorParam.newBuilder()
                .withCollectionName(collectionName())
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withExpr(ID_FIELD + " != ''")
                .withOutFields(List.of(ID_FIELD, METADATA_FIELD))
                .withBatchSize(500L)
                .build());
        if (response.getException() != null) throw new IllegalStateException("Milvus document scan failed", response.getException());
        QueryIterator iterator = response.getData();
        List<IndexedDocument> documents = new ArrayList<>();
        try {
            List<QueryResultsWrapper.RowRecord> rows;
            while (!(rows = iterator.next()).isEmpty()) {
                for (QueryResultsWrapper.RowRecord row : rows) {
                    documents.add(new IndexedDocument(String.valueOf(row.get(ID_FIELD)), metadata(row.get(METADATA_FIELD))));
                }
            }
        } finally {
            iterator.close();
        }
        return List.copyOf(documents);
    }

    private void checkField(Map<String, FieldSchema> fields, String name, DataType type, boolean primaryKey, List<String> violations) {
        FieldSchema field = fields.get(name);
        if (field == null) {
            violations.add("missing field " + name);
            return;
        }
        if (field.getDataType() != type) violations.add("field " + name + " type expected=" + type + " actual=" + field.getDataType());
        if (field.getIsPrimaryKey() != primaryKey) violations.add("field " + name + " primaryKey expected=" + primaryKey);
    }

    private int dimension(FieldSchema field) {
        for (var parameter : field.getTypeParamsList()) {
            if ("dim".equals(parameter.getKey())) return Integer.parseInt(parameter.getValue());
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Object value) {
        if (value == null) return Map.of();
        Gson gson = new Gson();
        if (value instanceof JsonObject json) return gson.fromJson(json, Map.class);
        return gson.fromJson(String.valueOf(value), Map.class);
    }

    private MilvusServiceClient client() {
        if (suppliedClient != null) return suppliedClient;
        if (vectorStore == null) throw new IllegalStateException("Milvus VectorStore is not configured");
        return vectorStore.getNativeClient()
                .filter(MilvusServiceClient.class::isInstance)
                .map(MilvusServiceClient.class::cast)
                .orElseThrow(() -> new IllegalStateException("Configured VectorStore is not Milvus"));
    }

    private String collectionName() { return suppliedCollectionName == null ? configuredCollectionName : suppliedCollectionName; }
    private int embeddingDimension() { return suppliedEmbeddingDimension == null ? configuredEmbeddingDimension : suppliedEmbeddingDimension; }

    public record SchemaPreflight(String collectionName, boolean compatible, List<String> violations) { }
    public record IndexedDocument(String documentId, Map<String, Object> metadata) { }
}
