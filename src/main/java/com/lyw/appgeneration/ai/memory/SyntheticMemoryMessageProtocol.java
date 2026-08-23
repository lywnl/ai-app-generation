package com.lyw.appgeneration.ai.memory;

/** 服务端合成记忆消息的保留标记协议。 */
public final class SyntheticMemoryMessageProtocol {

    public static final String RESERVED_PREFIX = "[[server.";

    public static final String TRUSTED_TURN_ACK =
            "[[server.synthetic-memory/trusted-turn/v1]]";
    public static final String L1_SUMMARY_ACK =
            "[[server.synthetic-memory/l1-summary/v1]]";
    public static final String L2_PREFERENCE_ACK =
            "[[server.synthetic-memory/l2-preference/v1]]";

    private SyntheticMemoryMessageProtocol() {
    }

    /** 仅识别协议定义的完整合成记忆标记。 */
    public static boolean isReservedMessage(String text) {
        return TRUSTED_TURN_ACK.equals(text)
                || L1_SUMMARY_ACK.equals(text)
                || L2_PREFERENCE_ACK.equals(text);
    }

    /** 识别任意使用服务端保留命名空间的文本。 */
    public static boolean containsReservedMarker(String text) {
        return text != null && text.contains(RESERVED_PREFIX);
    }
}
