package com.lyw.appgeneration.service.rag.monitor;

import com.lyw.appgeneration.service.rag.model.RankedCandidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Vue RAG 日志脱敏辅助方法，只暴露固定长度查询哈希和候选父文档 ID。
 */
public final class VueRagLogSanitizer {

    private static final int QUERY_HASH_LENGTH = 12;

    private VueRagLogSanitizer() {
    }

    public static String queryHash(String query) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((query == null ? "" : query)
                    .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes, 0, QUERY_HASH_LENGTH / 2);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    public static List<String> candidateIds(List<RankedCandidate> candidates) {
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> candidate != null
                        && candidate.documentId() != null
                        && !candidate.documentId().isBlank())
                .map(RankedCandidate::documentId)
                .toList();
    }
}
