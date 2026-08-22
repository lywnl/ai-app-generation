package com.lyw.appgeneration.rag.ingest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从 Milvus Collection 读取的一条 Vue 向量记录。
 */
record VueMilvusRow(
        String embeddingId,
        int vectorDimension,
        String text,
        Map<String, String> metadata) {

    VueMilvusRow {
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
