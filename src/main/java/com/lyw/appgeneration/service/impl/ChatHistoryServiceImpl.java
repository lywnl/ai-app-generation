package com.lyw.appgeneration.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.ConversationTurn;
import com.lyw.appgeneration.ai.memory.TokenAwareChatMemory;
import com.lyw.appgeneration.constants.UserConstant;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.ThrowUtils;
import com.lyw.appgeneration.model.dto.app.ChatHistoryQueryRequest;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.mapper.ChatHistoryMapper;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.ChatHistoryService;
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

    @Resource
    @Lazy
    private AppService appService;

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
        return addChatMessage(appId, aiMessage,
                ChatHistoryMessageTypeEnum.AI.getValue(), userId);
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
            //按照时间顺序添加到激励中
            int loadCount = 0;
            //先清理历史缓存 防止重复加载
            chatMemory.clear();
            for (ChatHistory chatHistory : history) {
                if (ChatHistoryMessageTypeEnum.USER.getValue().equals(chatHistory.getMessageType())) {
                    chatMemory.add(UserMessage.from(chatHistory.getMessage()));
                } else if (ChatHistoryMessageTypeEnum.AI.getValue().equals(chatHistory.getMessageType())) {
                    chatMemory.add(AiMessage.from(chatHistory.getMessage()));
                }
                loadCount++;
            }
            log.info("成功加载 {} 条对话历史到内存中，应用ID：{}", loadCount, appId);
            return HistoryLoadResult.loaded(loadCount);
        } catch (Exception e) {
            log.error("加载对话历史到内存失败,应用ID：{}", appId, e);
            return HistoryLoadResult.failed();
        }

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
        List<ConversationTurn> newestFirst = new ArrayList<>();
        ChatHistory pendingAi = null;
        Long beforeId = null;
        long totalTokens = 0L;
        while (totalTokens < tokenThreshold) {
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
                    pendingAi = history;
                } else if (isUserMessage(history) && pendingAi != null) {
                    ConversationTurn turn = toConversationTurn(
                            history, pendingAi, estimator);
                    newestFirst.add(turn);
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
                UserMessage.from(user.getMessage()),
                AiMessage.from(ai.getMessage()));
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
                    pendingAi = history;
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
                Objects.toString(user.getMessage(), ""),
                Objects.toString(ai.getMessage(), ""));
    }

    private record CompleteTurnLoad(
            List<ConversationTurn> turns, int tokens) {

        private CompleteTurnLoad {
            turns = List.copyOf(turns);
        }
    }

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
