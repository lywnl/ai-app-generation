package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.Map;
import java.util.Set;

/** 生成仅供浏览器展示的工具消息副本，不修改模型记忆中的原始参数与结果。 */
public final class ToolStreamMessageRedactor {

    private static final Map<String, String> SAFE_PATH_ARGUMENTS = Map.of(
            "readFile", "relativeFilePath",
            "readDir", "relativeDirPath",
            "writeFile", "relativeFilePath",
            "modifyFile", "relativeFilePath",
            "deleteFile", "relativeFilePath");
    private static final Set<String> READ_TOOLS = Set.of("readFile", "readDir");

    private ToolStreamMessageRedactor() {
    }

    public static String safeArguments(String toolName, String rawArguments) {
        JSONObject safe = new JSONObject(
                JSONConfig.create().setIgnoreNullValue(false));
        String pathArgument = SAFE_PATH_ARGUMENTS.get(toolName);
        if (pathArgument == null) {
            return JSONUtil.toJsonStr(safe);
        }
        try {
            JSONObject raw = JSONUtil.parseObj(
                    rawArguments, JSONConfig.create().setCheckDuplicate(true));
            Object path = raw.get(pathArgument);
            if (path instanceof String text) {
                safe.set(pathArgument, text);
            }
        } catch (RuntimeException ignored) {
            // 客户端副本宁可丢弃元数据，也不回传无法验证的原始参数。
        }
        return JSONUtil.toJsonStr(safe);
    }

    public static String safeResult(
            String toolName, String safeArguments, String rawResult) {
        if (!READ_TOOLS.contains(toolName)) {
            return rawResult;
        }
        String pathArgument = SAFE_PATH_ARGUMENTS.get(toolName);
        String relativePath = null;
        try {
            relativePath = JSONUtil.parseObj(safeArguments).getStr(pathArgument);
        } catch (RuntimeException ignored) {
            // 严格解析函数会把缺失路径判为不可信结果。
        }
        return FileToolProtocolSupport.clientSafeReadResult(
                rawResult, toolName, relativePath);
    }
}
