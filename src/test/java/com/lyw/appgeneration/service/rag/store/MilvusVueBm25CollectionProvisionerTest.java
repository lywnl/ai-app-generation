package com.lyw.appgeneration.service.rag.store;

import com.lyw.appgeneration.constants.RagConstants;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusVueBm25CollectionProvisionerTest {

    @Test
    void Collection不存在时创建包含BM25Function和双索引的完整协议() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        when(client.hasCollection(any())).thenReturn(false);
        MilvusVueBm25CollectionProvisioner provisioner = new MilvusVueBm25CollectionProvisioner();

        provisioner.ensureCollection(client, 1024);

        ArgumentCaptor<CreateCollectionReq> captor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(client).createCollection(captor.capture());
        CreateCollectionReq request = captor.getValue();
        assertEquals(RagConstants.VUE_BM25_COLLECTION, request.getCollectionName());
        assertNotNull(request.getCollectionSchema());
        assertFalse(request.getCollectionSchema().isEnableDynamicField());
        assertEquals(List.of("id", "text", "metadata", "vector", "bm25_sparse_vector"),
                request.getCollectionSchema().getFieldSchemaList().stream()
                        .map(CreateCollectionReq.FieldSchema::getName).toList());

        CreateCollectionReq.FieldSchema text = request.getCollectionSchema().getField("text");
        assertEquals(DataType.VarChar, text.getDataType());
        assertEquals(65535, text.getMaxLength());
        assertTrue(text.getEnableAnalyzer());
        assertEquals(DataType.SparseFloatVector,
                request.getCollectionSchema().getField("bm25_sparse_vector").getDataType());

        assertEquals(1, request.getCollectionSchema().getFunctionList().size());
        CreateCollectionReq.Function function = request.getCollectionSchema().getFunctionList().getFirst();
        assertEquals(FunctionType.BM25, function.getFunctionType());
        assertEquals(List.of("text"), function.getInputFieldNames());
        assertEquals(List.of("bm25_sparse_vector"), function.getOutputFieldNames());

        assertEquals(2, request.getIndexParams().size());
        IndexParam sparseIndex = request.getIndexParams().stream()
                .filter(index -> "bm25_sparse_vector".equals(index.getFieldName()))
                .findFirst().orElseThrow();
        assertEquals(IndexParam.IndexType.SPARSE_INVERTED_INDEX, sparseIndex.getIndexType());
        assertEquals(IndexParam.MetricType.BM25, sparseIndex.getMetricType());
        assertEquals(0.2, sparseIndex.getExtraParams().get("drop_ratio_build"));
    }

    @Test
    void Collection已存在时不重复创建() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        when(client.hasCollection(any())).thenReturn(true);
        MilvusVueBm25CollectionProvisioner provisioner = new MilvusVueBm25CollectionProvisioner();

        provisioner.ensureCollection(client, 1024);

        verify(client, never()).createCollection(any());
    }
}
