package com.lyw.appgeneration.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.ChatHistoryMemoryResolver;
import com.lyw.appgeneration.ai.memory.ConversationTurn;
import com.lyw.appgeneration.ai.memory.TokenAwareChatMemory;
import com.lyw.appgeneration.ai.memory.AdmissionDeadline;
import com.lyw.appgeneration.ai.memory.DecisionEvidence;
import com.lyw.appgeneration.ai.memory.DecisionEvidenceCodec;
import com.lyw.appgeneration.ai.memory.L0Snapshot;
import com.lyw.appgeneration.ai.memory.L0SourceOperations;
import com.lyw.appgeneration.ai.memory.DeadlineAwareReplaceResult;
import com.lyw.appgeneration.constants.UserConstant;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.ThrowUtils;
import com.lyw.appgeneration.model.dto.app.ChatHistoryQueryRequest;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.mapper.ChatHistoryMapper;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.MemorySummarySnapshot;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 对话历史 服务层实现。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Slf4j
@Service
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory>  implements ChatHistoryService {

    private static final int COMPLETE_TURN_LOAD_BATCH_SIZE = 100;
    private static final String ORPHAN_TURN_MEMORY_MESSAGE =
            "上一轮因系统中断未完成，最终构建结果未确认。已经落盘的修改保留，后续操作以当前工程文件为准。";

    private final ChatHistoryMemoryResolver memoryResolver;

    public ChatHistoryServiceImpl() {
        this(new ChatHistoryMemoryResolver());
    }

    @Autowired
    public ChatHistoryServiceImpl(ChatHistoryMemoryResolver memoryResolver) {
        this.memoryResolver = Objects.requireNonNull(
                memoryResolver, "聊天记忆投影解析器不能为空");
    }

    @Resource
    @Lazy
    private AppService appService;

    @Resource
    @Lazy
    private MemorySummaryService memorySummaryService;

    @Resource
    private com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence lifecycleFence;

    @Override
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId) {
        return addChatMessageAndReturn(
                appId, message, messageType, userId) != null;
    }

    @Override
    public ChatHistory addChatMessageAndReturn(
            Long appId, String message, String messageType, Long userId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(messageType), ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户ID不能为空");
        // 验证消息类型是否有效
        ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
        ThrowUtils.throwIf(messageTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的消息类型: " + messageType);
        ChatHistory chatHistory = ChatHistory.builder()
                .appId(appId)
                .message(message)
                .messageType(messageType)
                .userId(userId)
                .build();
        return this.save(chatHistory) ? chatHistory : null;
    }

    @Override
    public ChatHistory addAiMessageAndReturn(
            Long appId,
            String displayMessage,
            String memoryMessage,
            ChatMemoryOutcome memoryOutcome,
            Long userId) {
        ThrowUtils.throwIf(StrUtil.isBlank(memoryMessage),
                ErrorCode.PARAMS_ERROR, "AI 记忆投影不能为空");
        ThrowUtils.throwIf(memoryOutcome == null,
                ErrorCode.PARAMS_ERROR, "AI 记忆结果不能为空");
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(displayMessage),
                ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(userId == null || userId <= 0,
                ErrorCode.PARAMS_ERROR, "用户ID不能为空");
        ChatHistory chatHistory = ChatHistory.builder()
                .appId(appId)
                .message(displayMessage)
                .memoryMessage(memoryMessage)
                .memoryOutcome(memoryOutcome)
                .messageType(ChatHistoryMessageTypeEnum.AI.getValue())
                .userId(userId)
                .build();
        return this.save(chatHistory) ? chatHistory : null;
    }

    @Override
    public boolean deleteByAppId(Long appId) {
        ThrowUtils.throwIf(appId == null, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", appId);

        return this.remove(queryWrapper);
    }

    /**
     * 获取查询包装类
     *
     * @param chatHistoryQueryRequest
     * @return
     */
    @Override
    public QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest) {
        QueryWrapper queryWrapper = QueryWrapper.create();
        if (chatHistoryQueryRequest == null) {
            return queryWrapper;
        }
        Long id = chatHistoryQueryRequest.getId();
        String message = chatHistoryQueryRequest.getMessage();
        String messageType = chatHistoryQueryRequest.getMessageType();
        Long appId = chatHistoryQueryRequest.getAppId();
        Long userId = chatHistoryQueryRequest.getUserId();
        LocalDateTime lastCreateTime = chatHistoryQueryRequest.getLastCreateTime();
        String sortField = chatHistoryQueryRequest.getSortField();
        String sortOrder = chatHistoryQueryRequest.getSortOrder();
        // 拼接查询条件
        queryWrapper.eq("id", id)
                .like("message", message)
                .eq("messageType", messageType)
                .eq("appId", appId)
                .eq("userId", userId);
        // 游标查询逻辑 - 只使用 createTime 作为游标
        if (lastCreateTime != null) {
            queryWrapper.lt("createTime", lastCreateTime);
        }
        // 排序
        if (StrUtil.isNotBlank(sortField)) {
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        } else {
            // 默认按创建时间降序排列
            queryWrapper.orderBy("createTime", false);
        }
        return queryWrapper;
    }

    @Override
    public Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize,
                                                      LocalDateTime lastCreateTime,
                                                      User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > 50, ErrorCode.PARAMS_ERROR, "页面大小必须在1-50之间");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        // 验证权限：只有应用创建者和管理员可以查看
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话历史");
        // 构建查询条件
        ChatHistoryQueryRequest queryRequest = new ChatHistoryQueryRequest();
        queryRequest.setAppId(appId);
        queryRequest.setLastCreateTime(lastCreateTime);
        QueryWrapper queryWrapper = this.getQueryWrapper(queryRequest);
        // 查询数据
        return this.page(Page.of(1, pageSize), queryWrapper);
    }

    @Override
    public boolean existsByAppId(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        return this.count(QueryWrapper.create().eq("appId", appId)) > 0;
    }

    @Override
    public ChatHistory getLastMessage(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        List<ChatHistory> messages = this.list(QueryWrapper.create()
                .eq("appId", appId)
                .orderBy("id", false)
                .limit(1));
        return CollUtil.isEmpty(messages) ? null : messages.getFirst();
    }

    @Override
    public boolean repairOrphanUserTurn(
            Long appId, Long userId, String aiMessage) {
        ChatHistory lastMessage = getLastMessage(appId);
        if (lastMessage == null || !ChatHistoryMessageTypeEnum.USER.getValue()
                .equals(lastMessage.getMessageType())) {
            return false;
        }
        if (lastMessage.getId() == null || !Objects.equals(lastMessage.getAppId(), appId)
                || !Objects.equals(lastMessage.getUserId(), userId)) {
            throw new IllegalStateException("孤立用户记录归属不一致");
        }
        return Objects.requireNonNull(memorySummaryService, "孤立回合终态接口未装配")
                .persistRecoveredTurnOutcome(appId, userId, lastMessage.getId(), () -> {
                    ChatHistory locked = getOne(QueryWrapper.create().eq("id", lastMessage.getId())
                            .eq("appId", appId).eq("userId", userId).forUpdate());
                    ChatHistory latest = getLastMessage(appId);
                    if (locked == null || latest == null || !Objects.equals(latest.getId(), locked.getId())
                            || !isUserMessage(locked)) throw new IllegalStateException("待修复用户回合已变化");
                    return addAiMessageAndReturn(appId, aiMessage, ORPHAN_TURN_MEMORY_MESSAGE,
                            ChatMemoryOutcome.SYSTEM_ERROR, userId);
                }) != null;
    }

    @Override
    public HistoryLoadResult loadChatHistoryToMemory(
            Long appId, ChatMemory chatMemory, int maxCount) {
        try {
            QueryWrapper queryWrapper = QueryWrapper.create()
                    .eq("appId", appId)
                    .orderBy("id", false)
                    .limit(maxCount);
            List<ChatHistory> history = this.list(queryWrapper);
            if (history == null) {
                log.error("加载对话历史返回 null,应用ID：{}", appId);
                return HistoryLoadResult.failed();
            }
            if (history.isEmpty()) {
                return HistoryLoadResult.empty();
            }
            // DAO 返回集合不保证可变，复制后反转为稳定时间正序。
            history = new java.util.ArrayList<>(history);
            CollUtil.reverse(history);
            List<ChatMessage> messages = completeMessagesInOrder(history);
            chatMemory.clear();
            chatMemory.add(messages);
            int loadCount = messages.size();
            if (loadCount == 0) {
                return HistoryLoadResult.empty();
            }
            log.info("成功加载 {} 条对话历史到内存中，应用ID：{}", loadCount, appId);
            return HistoryLoadResult.loaded(loadCount);
        } catch (Exception e) {
            log.error("加载对话历史到内存失败,应用ID：{}", appId, e);
            return HistoryLoadResult.failed();
        }

    }

    private List<ChatMessage> completeMessagesInOrder(
            List<ChatHistory> historyRows) {
        List<ChatMessage> messages = new ArrayList<>();
        ChatHistory pendingUser = null;
        boolean invalidUserSequence = false;
        for (ChatHistory history : historyRows) {
            if (isUserMessage(history)) {
                if (pendingUser != null || invalidUserSequence) {
                    pendingUser = null;
                    invalidUserSequence = true;
                } else {
                    pendingUser = history;
                }
                continue;
            }
            if (!isAiMessage(history)) {
                pendingUser = null;
                invalidUserSequence = false;
                continue;
            }
            String aiText = memoryResolver.resolveModelText(history)
                    .orElse(null);
            if (invalidUserSequence || pendingUser == null || aiText == null) {
                pendingUser = null;
                invalidUserSequence = false;
                continue;
            }
            String userText = memoryResolver.resolveModelText(pendingUser)
                    .orElse(null);
            pendingUser = null;
            if (userText != null) {
                messages.add(UserMessage.from(userText));
                messages.add(AiMessage.from(aiText));
            }
        }
        return List.copyOf(messages);
    }

    @Override
    public HistoryLoadResult loadRecentCompleteTurnsToMemory(
            Long appId,
            ChatMemory chatMemory,
            int blockingCompressionThreshold,
            ChatTokenEstimator estimator) {
        return loadRecentCompleteTurnsToMemory(appId, 0L, chatMemory,
                blockingCompressionThreshold, estimator);
    }

    @Override
    public HistoryLoadResult loadRecentCompleteTurnsToMemory(
            Long appId,
            long afterCursorId,
            ChatMemory chatMemory,
            int blockingCompressionThreshold,
            ChatTokenEstimator estimator) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        Objects.requireNonNull(chatMemory, "L0 ChatMemory 不能为空");
        Objects.requireNonNull(estimator, "Token 估算器不能为空");
        if (afterCursorId < 0L) {
            throw new IllegalArgumentException("L1 摘要游标不能为负数");
        }
        if (blockingCompressionThreshold <= 0) {
            throw new IllegalArgumentException("阻塞压缩阈值必须大于 0");
        }
        try {
            List<ChatMessage> oldSnapshot =
                    List.copyOf(chatMemory.messages());
            CompleteTurnLoad load = readRecentCompleteTurns(
                    appId, afterCursorId,
                    blockingCompressionThreshold, estimator);
            int loadedMessages = replaceColdMemory(
                    chatMemory, oldSnapshot, load.turns());
            if (loadedMessages == 0) {
                return HistoryLoadResult.empty();
            }
            log.info("按完整回合加载 {} 条消息到 L0，应用ID：{}，估算Token：{}",
                    loadedMessages, appId, load.tokens());
            return HistoryLoadResult.loaded(loadedMessages);
        } catch (Exception exception) {
            log.error("按完整回合加载对话历史失败，应用ID：{}", appId, exception);
            return HistoryLoadResult.failed();
        }
    }

    private CompleteTurnLoad readRecentCompleteTurns(
            Long appId,
            long afterCursorId,
            int tokenThreshold,
            ChatTokenEstimator estimator) {
        return readRecentCompleteTurns(appId, afterCursorId, tokenThreshold, estimator, false, null);
    }

    @Override
    public HistoryLoadResult loadSourceHistoryToMemory(Long appId, MemorySummarySnapshot summary,
            TokenAwareChatMemory memory, int tokenThreshold, ChatTokenEstimator estimator, AdmissionDeadline deadline) {
        Objects.requireNonNull(summary);
        Objects.requireNonNull(memory);
        Objects.requireNonNull(deadline);
        if (appId == null || appId <= 0 || !Objects.equals(memory.id(), appId) || tokenThreshold <= 0) {
            throw new IllegalArgumentException("来源冷重建的应用或预算无效");
        }
        try (var writer = Objects.requireNonNull(lifecycleFence, "来源重建生命周期栅栏未装配").tryAcquireWriter(appId)) {
            if (writer == null) throw new IllegalStateException("应用删除流程已接管来源冷重建");
            requireLoadTime(deadline);
            L0Snapshot old = memory.sourceSnapshot();
            if (old.hasKnownSource() && old.appliedSummaryRevision() > summary.summaryRevision()) {
                throw new IllegalStateException("冷重建所读摘要落后于原窗口依赖");
            }
            CompleteTurnLoad load = readRecentCompleteTurns(appId, summary.lastSummarizedId(), tokenThreshold,
                    estimator, true, deadline);
            if (!old.hasKnownSource() && !old.entries().isEmpty() && load.sources().isEmpty() && summary.summary().isEmpty()) {
                throw new IllegalStateException("旧数组缺少可对齐的持久历史或有效摘要");
            }
            L0Snapshot rebuilt = L0Snapshot.empty(Long.toString(appId), summary.summaryRevision());
            for (var persisted : load.sources()) {
                var turn = persisted.turn();
                var frontier = summary.coverageState();
                String turnId = persisted.evidence() == null ? "history-" + turn.turnId() : persisted.evidence().turnId();
                if (frontier != null && frontier.userHistoryId() == turn.turnId()) {
                    if (persisted.evidence() != null && !frontier.turnId().equals(turnId)) {
                        throw new IllegalStateException("旧部分前沿与持久证据归属不一致");
                    }
                    turnId = frontier.turnId();
                }
                rebuilt = L0SourceOperations.importClosedHistory(rebuilt, turn, turnId, persisted.outcome());
            }
            requireLoadTime(deadline);
            if (memory.replaceSourceSnapshot(old, rebuilt, deadline) != DeadlineAwareReplaceResult.REPLACED) {
                throw new IllegalStateException("来源冷重建提交未确认");
            }
            int count = rebuilt.entries().size();
            return count == 0 ? HistoryLoadResult.empty() : HistoryLoadResult.loaded(count);
        } catch (RuntimeException failure) {
            log.warn("来源冷重建失败 appId={} type={}", appId, failure.getClass().getSimpleName());
            return HistoryLoadResult.failed();
        }
    }

    private void requireLoadTime(AdmissionDeadline deadline) {
        if (deadline != null && (deadline.remainingNanos() <= 0 || Thread.currentThread().isInterrupted())) {
            throw new java.util.concurrent.CancellationException("冷重建超过截止或已中断");
        }
    }

    private CompleteTurnLoad readRecentCompleteTurns(Long appId, long afterCursorId, int tokenThreshold,
            ChatTokenEstimator estimator, boolean sourceRequired, AdmissionDeadline deadline) {
        List<PersistedTurn> newestFirst = new ArrayList<>();
        ChatHistory pendingAi = null;
        Long beforeId = null;
        long totalTokens = 0L;
        while (totalTokens < tokenThreshold) {
            requireLoadTime(deadline);
            List<ChatHistory> batch = listRecentHistoryBatch(
                    appId, afterCursorId, beforeId);
            if (batch == null) {
                throw new IllegalStateException("数据库返回了 null 历史批次");
            }
            if (batch.isEmpty()) {
                break;
            }
            long oldestId = requireDescendingBatchCursor(batch, beforeId);
            for (ChatHistory history : batch) {
                if (isAiMessage(history)) {
                    pendingAi = memoryResolver.resolveModelText(history)
                            .isPresent() ? history : null;
                } else if (isUserMessage(history) && pendingAi != null) {
                    ConversationTurn turn = toConversationTurn(
                            history, pendingAi, estimator);
                    DecisionEvidence evidence = null;
                    if (sourceRequired) {
                        if (!Objects.equals(history.getAppId(), appId) || !Objects.equals(pendingAi.getAppId(), appId)
                                || history.getUserId() == null || history.getUserId() <= 0
                                || !Objects.equals(history.getUserId(), pendingAi.getUserId())) {
                            throw new IllegalStateException("冷重建的持久回合归属无效");
                        }
                        evidence = DecisionEvidenceCodec.decode(history.getDecisionEvidence(), history.getId());
                    }
                    newestFirst.add(new PersistedTurn(turn, pendingAi.getMemoryOutcome(), evidence));
                    totalTokens = Math.min(Integer.MAX_VALUE,
                            totalTokens + turn.tokens());
                    pendingAi = null;
                    if (totalTokens >= tokenThreshold) {
                        break;
                    }
                } else {
                    pendingAi = null;
                }
            }
            if (totalTokens >= tokenThreshold
                    || batch.size() < COMPLETE_TURN_LOAD_BATCH_SIZE) {
                break;
            }
            beforeId = oldestId;
        }
        Collections.reverse(newestFirst);
        return new CompleteTurnLoad(newestFirst, (int) totalTokens);
    }

    private List<ChatHistory> listRecentHistoryBatch(
            Long appId, long afterCursorId, Long beforeId) {
        QueryWrapper query = QueryWrapper.create()
                .eq("appId", appId)
                .gt("id", afterCursorId);
        if (beforeId != null) {
            query.lt("id", beforeId);
        }
        return this.list(query.orderBy("id", false)
                .limit(COMPLETE_TURN_LOAD_BATCH_SIZE));
    }

    private long requireDescendingBatchCursor(
            List<ChatHistory> batch, Long beforeId) {
        Long oldestId = batch.getLast().getId();
        if (oldestId == null || oldestId <= 0L
                || (beforeId != null && oldestId >= beforeId)) {
            throw new IllegalStateException("历史批次游标无效");
        }
        return oldestId;
    }

    private ConversationTurn toConversationTurn(
            ChatHistory user,
            ChatHistory ai,
            ChatTokenEstimator estimator) {
        if (user.getId() == null || ai.getId() == null
                || user.getId() >= ai.getId()) {
            throw new IllegalStateException("完整回合 ID 顺序无效");
        }
        List<ChatMessage> messages = List.of(
                UserMessage.from(memoryResolver.resolveModelText(user)
                        .orElseThrow(() -> new IllegalStateException(
                                "完整回合用户文本无效"))),
                AiMessage.from(memoryResolver.resolveModelText(ai)
                        .orElseThrow(() -> new IllegalStateException(
                                "完整回合 AI 投影无效"))));
        int tokens = estimator.estimateMessages(messages);
        if (tokens <= 0) {
            throw new IllegalStateException("完整回合 Token 估算无效");
        }
        return new ConversationTurn(
                user.getId(), ai.getId(), messages, tokens);
    }

    private int replaceColdMemory(
            ChatMemory chatMemory,
            List<ChatMessage> oldSnapshot,
            List<ConversationTurn> turns) {
        List<ChatMessage> messages = turns.stream()
                .flatMap(turn -> turn.messages().stream())
                .toList();
        if (chatMemory instanceof TokenAwareChatMemory tokenAwareMemory) {
            if (!tokenAwareMemory.replaceSnapshotIfMatches(
                    oldSnapshot, messages)) {
                throw new IllegalStateException("L0 冷重建快照替换失败");
            }
        } else {
            if (!List.copyOf(chatMemory.messages()).equals(oldSnapshot)) {
                throw new IllegalStateException("L0 冷重建期间窗口已变化");
            }
            chatMemory.clear();
            chatMemory.add(messages);
        }
        return messages.size();
    }

    private boolean isUserMessage(ChatHistory history) {
        return history != null && ChatHistoryMessageTypeEnum.USER.getValue()
                .equals(history.getMessageType());
    }

    private boolean isAiMessage(ChatHistory history) {
        return history != null && ChatHistoryMessageTypeEnum.AI.getValue()
                .equals(history.getMessageType());
    }

    @Override
    public List<StableTurnBoundary> listRecentCompleteTurnBoundaries(
            Long appId, int maxTurns) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("稳定回合数量必须大于 0");
        }
        List<StableTurnBoundary> newestFirst = new ArrayList<>(maxTurns);
        ChatHistory pendingAi = null;
        Long beforeId = null;
        while (newestFirst.size() < maxTurns) {
            List<ChatHistory> batch = listRecentHistoryBatch(
                    appId, 0L, beforeId);
            if (batch == null) {
                throw new IllegalStateException("数据库返回了 null 历史批次");
            }
            if (batch.isEmpty()) {
                break;
            }
            long oldestId = requireDescendingBatchCursor(batch, beforeId);
            for (ChatHistory history : batch) {
                if (isAiMessage(history)) {
                    pendingAi = memoryResolver.resolveModelText(history)
                            .isPresent() ? history : null;
                } else if (isUserMessage(history) && pendingAi != null) {
                    newestFirst.add(toStableTurnBoundary(
                            history, pendingAi));
                    pendingAi = null;
                    if (newestFirst.size() >= maxTurns) {
                        break;
                    }
                } else {
                    pendingAi = null;
                }
            }
            if (newestFirst.size() >= maxTurns
                    || batch.size() < COMPLETE_TURN_LOAD_BATCH_SIZE) {
                break;
            }
            beforeId = oldestId;
        }
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    private StableTurnBoundary toStableTurnBoundary(
            ChatHistory user, ChatHistory ai) {
        if (user.getId() == null || ai.getId() == null) {
            throw new IllegalStateException("稳定回合 ID 不能为空");
        }
        return new StableTurnBoundary(
                user.getId(), ai.getId(),
                memoryResolver.resolveModelText(user).orElseThrow(
                        () -> new IllegalStateException(
                                "稳定回合用户文本无效")),
                memoryResolver.resolveModelText(ai).orElseThrow(
                        () -> new IllegalStateException(
                                "稳定回合 AI 投影无效")));
    }

    private record CompleteTurnLoad(
            List<PersistedTurn> sources, int tokens) {

        private CompleteTurnLoad {
            sources = List.copyOf(sources);
        }
        private List<ConversationTurn> turns() { return sources.stream().map(PersistedTurn::turn).toList(); }
    }

    private record PersistedTurn(ConversationTurn turn, ChatMemoryOutcome outcome, DecisionEvidence evidence) { }

    @Override
    public List<ChatHistory> listMessagesAfterCursor(Long appId, Long cursorId, int limit) {
        // 游标用 chat_history.id（snowflake，单实例近似单调）；id > cursor 即「游标之后」的新消息
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", appId)
                .gt("id", cursorId == null ? 0L : cursorId)
                .orderBy("id", true)   // 正序：id 单调近似时间序
                .limit(limit);
        return this.list(queryWrapper);
    }

}
