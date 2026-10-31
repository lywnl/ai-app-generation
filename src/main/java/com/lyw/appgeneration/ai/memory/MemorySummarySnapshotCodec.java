package com.lyw.appgeneration.ai.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.MemorySummarySnapshot;

/** 兼容召回缓存保存完整版本快照；纯正文旧缓存无法提供覆盖边界。 */
public final class MemorySummarySnapshotCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private MemorySummarySnapshotCodec() { }

    public static String encode(long appId, MemorySummarySnapshot snapshot) {
        try { return JSON.writeValueAsString(new Envelope(1, appId, snapshot)); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("摘要快照缓存编码失败", exception); }
    }

    public static MemorySummarySnapshot decode(long appId, String value) {
        if (value == null || value.length() > 65_536) throw new IllegalArgumentException("摘要快照缓存缺失或超限");
        try {
            Envelope envelope = JSON.readValue(value, Envelope.class);
            if (envelope == null || envelope.formatVersion() != 1 || envelope.appId() != appId || envelope.snapshot() == null) {
                throw new IllegalArgumentException("摘要缓存身份或版本无效");
            }
            return envelope.snapshot();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("摘要快照缓存格式无效", exception);
        }
    }

    private record Envelope(int formatVersion, long appId, MemorySummarySnapshot snapshot) { }
}
