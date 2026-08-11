package com.lyw.appgeneration.rag.ingest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 从 PGVector 物理表读取的一条 Vue 向量记录。
 */
record VuePgVectorRow(
        UUID embeddingId,
        int vectorDimension,
        String text,
        Map<String, String> metadata) {

    VuePgVectorRow {
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
