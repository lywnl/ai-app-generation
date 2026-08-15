package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.model.dto.app.ChatHistoryQueryRequest;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import dev.langchain4j.memory.ChatMemory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 对话历史 服务层。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 添加对话消息到数据库
     * @param appId
     * @param message
     * @param messageType
     * @param userId
     * @return
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);

    /**
     * 根据应用ID删除对话消息
     * @param appId
     * @return
     */
    boolean deleteByAppId(Long appId);

    /**
     * 获取查询条件
     * @param chatHistoryQueryRequest
     * @return
     */
    QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest);

    /**
     * 根据应用ID分页获取对话消息
     * @param appId
     * @param pageSize
     * @param lastCreateTime
     * @param loginUser
     * @return
     */
    Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize, LocalDateTime lastCreateTime, User loginUser);

    /**
     * 加载对话历史到内存
     * @param appId
     * @param chatMemory
     * @param maxCount
     * @return
     */
    HistoryLoadResult loadChatHistoryToMemory(
            Long appId, ChatMemory chatMemory, int maxCount);

    /**
     * 按完整 User/AI 回合从新到旧回填 L0，累计达到阻塞压缩阈值后停止。
     *
     * <p>查询和回合识别全部成功后才替换 ChatMemory，避免数据库分批读取失败时
     * 先清空现有 L0。若全部完整历史不足阈值，则完整回填。</p>
     *
     * @param appId 应用 ID
     * @param chatMemory 待重建的 L0
     * @param blockingCompressionThreshold 阻塞压缩阈值
     * @param estimator 统一 Token 估算器
     * @return 加载状态和实际回填的消息条数
     */
    default HistoryLoadResult loadRecentCompleteTurnsToMemory(
            Long appId,
            ChatMemory chatMemory,
            int blockingCompressionThreshold,
            ChatTokenEstimator estimator) {
        return loadRecentCompleteTurnsToMemory(appId, 0L, chatMemory,
                blockingCompressionThreshold, estimator);
    }

    /**
     * 只回填 L1 摘要游标之后的稳定完整回合。
     */
    HistoryLoadResult loadRecentCompleteTurnsToMemory(
            Long appId,
            long afterCursorId,
            ChatMemory chatMemory,
            int blockingCompressionThreshold,
            ChatTokenEstimator estimator);

    /** 查询最近稳定完整回合边界，结果按时间正序返回。 */
    List<StableTurnBoundary> listRecentCompleteTurnBoundaries(
            Long appId, int maxTurns);

    record StableTurnBoundary(
            long turnId,
            long completedThroughId,
            String userText,
            String aiText) {

        public StableTurnBoundary {
            if (turnId <= 0L || completedThroughId <= turnId) {
                throw new IllegalArgumentException("稳定回合 ID 边界无效");
            }
            userText = Objects.requireNonNull(userText, "用户文本不能为空");
            aiText = Objects.requireNonNull(aiText, "AI 文本不能为空");
        }
    }

    enum HistoryLoadStatus {
        LOADED,
        EMPTY,
        FAILED
    }

    record HistoryLoadResult(HistoryLoadStatus status, int count) {

        public HistoryLoadResult {
            java.util.Objects.requireNonNull(status, "status 不能为空");
            if (count < 0 || (status == HistoryLoadStatus.EMPTY && count != 0)
                    || (status == HistoryLoadStatus.FAILED && count != 0)
                    || (status == HistoryLoadStatus.LOADED && count == 0)) {
                throw new IllegalArgumentException("历史加载状态与数量不匹配");
            }
        }

        public static HistoryLoadResult loaded(int count) {
            return new HistoryLoadResult(HistoryLoadStatus.LOADED, count);
        }

        public static HistoryLoadResult empty() {
            return new HistoryLoadResult(HistoryLoadStatus.EMPTY, 0);
        }

        public static HistoryLoadResult failed() {
            return new HistoryLoadResult(HistoryLoadStatus.FAILED, 0);
        }
    }

    /**
     * 判断指定应用是否已存在任何对话记录（用于「是否首次对话」判定）
     * @param appId 应用 ID
     * @return 已有记录返回 true
     */
    boolean existsByAppId(Long appId);

    /** 查询应用最后一条持久化消息；不存在时返回 null。 */
    ChatHistory getLastMessage(Long appId);

    /**
     * 将上一轮孤立 User 补成稳定的 SYSTEM_ERROR AI 边界；无孤立 User 时不写入。
     */
    boolean repairOrphanUserTurn(Long appId, Long userId, String aiMessage);

    /**
     * 查询 appId 下 id 大于 cursorId 的消息，按 id 正序，最多 limit 条。
     * <p>供 L1 滚动摘要增量提炼：游标之后、热窗口之前的「待折叠」消息。
     *
     * @param appId    应用 ID
     * @param cursorId 游标（已摘要覆盖到的 chat_history.id），null 视为 0
     * @param limit    最多返回条数
     * @return 按 id 正序的消息列表
     */
    List<ChatHistory> listMessagesAfterCursor(Long appId, Long cursorId, int limit);
}
