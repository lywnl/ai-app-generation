package com.lyw.appgeneration.ai.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticMemoryMessageProtocolTest {

    @Test
    void 固定标记必须与协议完全一致() {
        assertEquals("[[server.", SyntheticMemoryMessageProtocol.RESERVED_PREFIX);
        assertEquals("[[server.synthetic-memory/trusted-turn/v1]]",
                SyntheticMemoryMessageProtocol.TRUSTED_TURN_ACK);
        assertEquals("[[server.synthetic-memory/l1-summary/v1]]",
                SyntheticMemoryMessageProtocol.L1_SUMMARY_ACK);
        assertEquals("[[server.synthetic-memory/l2-preference/v1]]",
                SyntheticMemoryMessageProtocol.L2_PREFERENCE_ACK);
    }

    @Test
    void 完整保留消息只接受三个固定标记() {
        assertTrue(SyntheticMemoryMessageProtocol.isReservedMessage(
                SyntheticMemoryMessageProtocol.TRUSTED_TURN_ACK));
        assertTrue(SyntheticMemoryMessageProtocol.isReservedMessage(
                SyntheticMemoryMessageProtocol.L1_SUMMARY_ACK));
        assertTrue(SyntheticMemoryMessageProtocol.isReservedMessage(
                SyntheticMemoryMessageProtocol.L2_PREFERENCE_ACK));

        assertFalse(SyntheticMemoryMessageProtocol.isReservedMessage(null));
        assertFalse(SyntheticMemoryMessageProtocol.isReservedMessage(""));
        assertFalse(SyntheticMemoryMessageProtocol.isReservedMessage("  "));
        assertFalse(SyntheticMemoryMessageProtocol.isReservedMessage("[[server."));
        assertFalse(SyntheticMemoryMessageProtocol.isReservedMessage(
                "正文" + SyntheticMemoryMessageProtocol.TRUSTED_TURN_ACK));
        assertFalse(SyntheticMemoryMessageProtocol.isReservedMessage(
                SyntheticMemoryMessageProtocol.L1_SUMMARY_ACK + "正文"));
        assertFalse(SyntheticMemoryMessageProtocol.isReservedMessage(
                "[[server.synthetic-memory/unknown/v1]]"));
    }

    @Test
    void 保留标记检测识别统一命名空间() {
        assertTrue(SyntheticMemoryMessageProtocol.containsReservedMarker(
                SyntheticMemoryMessageProtocol.TRUSTED_TURN_ACK));
        assertTrue(SyntheticMemoryMessageProtocol.containsReservedMarker(
                SyntheticMemoryMessageProtocol.L1_SUMMARY_ACK));
        assertTrue(SyntheticMemoryMessageProtocol.containsReservedMarker(
                SyntheticMemoryMessageProtocol.L2_PREFERENCE_ACK));
        assertTrue(SyntheticMemoryMessageProtocol.containsReservedMarker(
                "正文" + SyntheticMemoryMessageProtocol.TRUSTED_TURN_ACK + "正文"));
        assertTrue(SyntheticMemoryMessageProtocol.containsReservedMarker(
                "[[server.synthetic-memory/unknown/v1]]"));

        assertFalse(SyntheticMemoryMessageProtocol.containsReservedMarker(null));
        assertFalse(SyntheticMemoryMessageProtocol.containsReservedMarker(""));
        assertFalse(SyntheticMemoryMessageProtocol.containsReservedMarker("  "));
        assertFalse(SyntheticMemoryMessageProtocol.containsReservedMarker("[server."));
        assertFalse(SyntheticMemoryMessageProtocol.containsReservedMarker("[[server"));
        assertFalse(SyntheticMemoryMessageProtocol.containsReservedMarker("普通用户正文"));
    }
}
