package com.lyw.appgeneration.manger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用文件写入状态管理器
 * <p>
 * 针对 Vue 工程生成的工具循环场景,跟踪每个 appId 写过的文件及其内容指纹,
 * 让 FileWriteTool 能向 LLM 返回"已写入文件清单"和"是否重复写入"信号,
 * 打破 LLM 在长工具循环中"忘记已写过哪些文件"导致的重复写入死循环。
 *
 * @author lyw
 */
@Slf4j
@Component
public class AppFileStateManager {

    /** appId → (相对路径 → 内容 md5 指纹) */
    private final Map<Long, Map<String, String>> state = new ConcurrentHashMap<>();

    /** appId → (相对路径 → 被重复写入的次数),用于感知异常循环 */
    private final Map<Long, Map<String, Integer>> writeCountMap = new ConcurrentHashMap<>();

    public WriteResult recordWrite(Long appId, String relativePath, String content) {
        if (appId == null) appId = -1L;
        String newHash = md5(content);
        Map<String, String> files = state.computeIfAbsent(appId, k -> new ConcurrentHashMap<>());
        Map<String, Integer> counts = writeCountMap.computeIfAbsent(appId, k -> new ConcurrentHashMap<>());

        String oldHash = files.get(relativePath);
        int newCount = counts.merge(relativePath, 1, Integer::sum);

        WriteResult result = new WriteResult();
        result.totalFiles = files.size() + (oldHash == null ? 1 : 0);
        result.writeCount = newCount;

        if (oldHash == null) {
            result.status = WriteStatus.FIRST_TIME;
        } else if (oldHash.equals(newHash)) {
            result.status = WriteStatus.DUPLICATE_SAME_CONTENT;
        } else {
            result.status = WriteStatus.DUPLICATE_DIFFERENT_CONTENT;
        }

        files.put(relativePath, newHash);
        result.allFiles = List.copyOf(files.keySet());
        return result;
    }

    /**
     * 清理某个 appId 的状态(会话结束或用户主动重置时调用)
     */
    public void reset(Long appId) {
        if (appId == null) return;
        state.remove(appId);
        writeCountMap.remove(appId);
    }

    /**
     * 获取当前已写入文件数量
     */
    public int fileCount(Long appId) {
        if (appId == null) return 0;
        Map<String, String> files = state.get(appId);
        return files == null ? 0 : files.size();
    }

    private static String md5(String s) {
        if (s == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    public enum WriteStatus {
        FIRST_TIME,
        DUPLICATE_SAME_CONTENT,
        DUPLICATE_DIFFERENT_CONTENT
    }

    public static class WriteResult {
        public WriteStatus status;
        /** 当前 appId 下所有已写入的相对路径 */
        public List<String> allFiles;
        /** 累计写入数量 */
        public int totalFiles;
        /** 该路径被写入的次数(本次包含在内) */
        public int writeCount;
    }
}
