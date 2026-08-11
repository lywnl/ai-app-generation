package com.lyw.appgeneration.manger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

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

    private static final int STATE_LOCK_COUNT = 64;

    /** appId → 受同一把锁保护的文件指纹和写入次数 */
    private final Map<Long, AppWriteState> states = new ConcurrentHashMap<>();

    /** 固定数量条带锁，避免 appId 动态锁对象被删除后产生脱离状态竞态 */
    private final List<Object> stateLocks = IntStream.range(0, STATE_LOCK_COUNT)
            .mapToObj(ignored -> new Object())
            .toList();

    /**
     * 在 appId 级锁内分类写入；只有真实写入成功后才提交指纹和次数。
     */
    public WriteResult writeAndRecord(
            Long appId,
            String relativePath,
            String content,
            WriteOperation writeOperation) throws IOException {
        Long stateAppId = appId == null ? -1L : appId;
        String newHash = md5(content);
        synchronized (stateLock(stateAppId)) {
            AppWriteState appState = states.get(stateAppId);
            WriteStatus status = classify(
                    appState == null ? null : appState.files.get(relativePath), newHash);
            if (status != WriteStatus.DUPLICATE_SAME_CONTENT) {
                writeOperation.write();
            }
            if (appState == null) {
                appState = new AppWriteState();
                states.put(stateAppId, appState);
            }
            int writeCount = appState.writeCounts.merge(relativePath, 1, Integer::sum);
            appState.files.put(relativePath, newHash);
            return buildResult(appState, status, writeCount);
        }
    }

    int trackedAppCount() {
        return states.size();
    }

    private WriteStatus classify(String oldHash, String newHash) {
        if (oldHash == null) {
            return WriteStatus.FIRST_TIME;
        }
        return oldHash.equals(newHash)
                ? WriteStatus.DUPLICATE_SAME_CONTENT
                : WriteStatus.DUPLICATE_DIFFERENT_CONTENT;
    }

    private WriteResult buildResult(AppWriteState appState, WriteStatus status, int writeCount) {
        WriteResult result = new WriteResult();
        result.status = status;
        result.totalFiles = appState.files.size();
        result.writeCount = writeCount;
        result.allFiles = List.copyOf(appState.files.keySet());
        return result;
    }

    /**
     * 清理某个 appId 的状态(会话结束或用户主动重置时调用)
     */
    public void reset(Long appId) {
        if (appId == null) return;
        synchronized (stateLock(appId)) {
            states.remove(appId);
        }
    }

    /**
     * 获取当前已写入文件数量
     */
    public int fileCount(Long appId) {
        if (appId == null) return 0;
        synchronized (stateLock(appId)) {
            AppWriteState appState = states.get(appId);
            if (appState == null) {
                return 0;
            }
            return appState.files.size();
        }
    }

    private Object stateLock(Long appId) {
        return stateLocks.get(Math.floorMod(appId.hashCode(), STATE_LOCK_COUNT));
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

    @FunctionalInterface
    public interface WriteOperation {

        void write() throws IOException;
    }

    private static final class AppWriteState {

        private final Map<String, String> files = new HashMap<>();
        private final Map<String, Integer> writeCounts = new HashMap<>();
    }

}
