package com.lyw.appgeneration.ai.tools;

import java.util.Map;
import java.util.Set;

/**
 * 工具参数流式策略声明:声明某工具的哪些参数 key 需要"实时增量"下发。
 * 未命中的 key 走 buffered 模式,等 value 完整后一次性下发。
 */
public final class ToolStreamingSpec {

    /** key: 工具 name；value: 需要增量流式下发的参数 key 集合 */
    private static final Map<String, Set<String>> STREAMING_KEYS = Map.of(
            "writeFile", Set.of("content"),
            "modifyFile", Set.of("oldContent", "newContent")
    );

    private ToolStreamingSpec() {}

    public static boolean isStreaming(String toolName, String argKey) {
        Set<String> keys = STREAMING_KEYS.get(toolName);
        return keys != null && keys.contains(argKey);
    }
}
