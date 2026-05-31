package com.lyw.appgeneration.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.memory.MemorySummaryPromptBuilder;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * L1 滚动摘要服务实现。
 *
 * <p>触发:对话结束钩子异步调用;single-flight 防同 app 并发;
 * 失败 best-effort——游标不前进、failCount++、circuit breaker(连续失败 ≥3 暂停)。
 * 异步线程只读 {@code chat_history}、只写 {@code app_memory_summary},不碰 Redis/delegate,零竞态。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Slf4j
@Service
public class MemorySummaryServiceImpl implements MemorySummaryService {

    /** 生产默认:单次提炼最多并入的新消息条数(留 buffer 给 L0,容忍重叠)。 */
    private static final int DEFAULT_MAX_MESSAGES_PER_RUN = 60;
    /** 生产默认:触发提炼的最小新增条数(低于则跳过,避免高频小提炼)。 */
    private static final int DEFAULT_MIN_NEW_MESSAGES_TO_SUMMARIZE = 8;
    /** circuit breaker:连续失败上限。 */
    private static final int MAX_FAIL = 3;
    /** L1 摘要缓存键前缀:mem:summary:{appId}。 */
    private static final String CACHE_KEY_PREFIX = "mem:summary:";
    /** L1 摘要缓存 TTL:与 L0 热窗口对齐(application.yml spring.data.redis.ttl=3600=1h)。 */
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final ChatHistoryService chatHistoryService;
    private final AppMemorySummaryMapper summaryMapper;
    private final ChatModel summarizationModel;
    private final ExecutorService executor;
    private final StringRedisTemplate redisTemplate;
    private final int minNewMessagesToSummarize;
    private final int maxMessagesPerRun;

    /** single-flight:正在提炼的 appId。 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 生产构造器:复用 {@code openAiChatModel}(deepseek-v4-flash 非流式)+ 后台摘要线程池,采用默认阈值。
     * 两构造器并存,须 {@code @Autowired} 显式指定 Spring 注入此构造器。
     */
    @Autowired
    public MemorySummaryServiceImpl(ChatHistoryService chatHistoryService,
                                    AppMemorySummaryMapper summaryMapper,
                                    @Qualifier("openAiChatModel") ChatModel summarizationModel,
                                    @Qualifier("memorySummarizationExecutor") ExecutorService executor,
                                    StringRedisTemplate redisTemplate) {
        this(chatHistoryService, summaryMapper, summarizationModel, executor, redisTemplate,
                DEFAULT_MIN_NEW_MESSAGES_TO_SUMMARIZE, DEFAULT_MAX_MESSAGES_PER_RUN);
    }

    /** 全参构造器:显式阈值,供单测注入小阈值以聚焦核心逻辑(滚动/游标/降级)。 */
    MemorySummaryServiceImpl(ChatHistoryService chatHistoryService,
                             AppMemorySummaryMapper summaryMapper,
                             ChatModel summarizationModel,
                             ExecutorService executor,
                             StringRedisTemplate redisTemplate,
                             int minNewMessagesToSummarize,
                             int maxMessagesPerRun) {
        this.chatHistoryService = chatHistoryService;
        this.summaryMapper = summaryMapper;
        this.summarizationModel = summarizationModel;
        this.executor = executor;
        this.redisTemplate = redisTemplate;
        this.minNewMessagesToSummarize = minNewMessagesToSummarize;
        this.maxMessagesPerRun = maxMessagesPerRun;
    }

    @Override
    public void triggerSummarizationAsync(Long appId) {
        if (appId == null || appId <= 0) {
            return;
        }
        if (!inFlight.add(appId)) {
            return; // single-flight:已在提炼则跳过
        }
        try {
            executor.submit(() -> {
                try {
                    summarizeNow(appId);
                } finally {
                    inFlight.remove(appId);
                }
            });
        } catch (Exception e) { // 线程池拒绝等
            inFlight.remove(appId);
            log.warn("提交摘要任务失败 appId={}: {}", appId, e.getMessage());
        }
    }

    /** 同步提炼一次(供测试与 {@link #triggerSummarizationAsync} 内部调用)。best-effort,不抛异常。 */
    public void summarizeNow(Long appId) {
        try {
            AppMemorySummary current = summaryMapper.selectOneByQuery(
                    QueryWrapper.create().eq("appId", appId));
            if (current != null && current.getFailCount() != null && current.getFailCount() >= MAX_FAIL) {
                log.warn("appId={} 摘要连续失败 {} 次,circuit breaker 暂停", appId, current.getFailCount());
                return;
            }
            long cursor = (current == null || current.getLastSummarizedId() == null)
                    ? 0L : current.getLastSummarizedId();
            List<ChatHistory> news = chatHistoryService.listMessagesAfterCursor(appId, cursor, maxMessagesPerRun);
            if (CollUtil.isEmpty(news) || news.size() < minNewMessagesToSummarize) {
                return; // 新增不足,跳过
            }
            String oldSummary = current == null ? "" : StrUtil.nullToEmpty(current.getSummary());
            String newText = renderMessages(news);
            String prompt = MemorySummaryPromptBuilder.build(oldSummary, newText);

            String newSummary;
            try {
                newSummary = summarizationModel.chat(prompt);
            } catch (Exception modelErr) { // 模型失败:游标不前进,failCount++
                log.error("摘要模型调用失败 appId={}: {}", appId, modelErr.getMessage());
                bumpFail(appId, current);
                return;
            }

            long newCursor = news.get(news.size() - 1).getId();
            upsert(appId, current, newSummary, newCursor);
            log.info("摘要更新成功 appId={},游标 {} -> {}", appId, cursor, newCursor);
        } catch (Exception e) { // 兜底:绝不影响主流程
            log.error("摘要提炼异常 appId={}: {}", appId, e.getMessage(), e);
        }
    }

    @Override
    public String getCurrentSummary(Long appId) {
        String cacheKey = CACHE_KEY_PREFIX + appId;
        // 1) 先读 Redis 缓存:命中(含空串)直接返回,堵住工具循环内的 N+1 MySQL 读
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached;
            }
        } catch (Exception e) {
            log.warn("读取摘要缓存失败 appId={}: {}", appId, e.getMessage()); // 降级:继续查 MySQL
        }
        // 2) 未命中:查 MySQL
        String summary;
        try {
            AppMemorySummary s = summaryMapper.selectOneByQuery(QueryWrapper.create().eq("appId", appId));
            summary = s == null ? "" : StrUtil.nullToEmpty(s.getSummary());
        } catch (Exception e) {
            log.warn("读取摘要失败 appId={}: {}", appId, e.getMessage());
            return ""; // 降级:无摘要,只用 L0
        }
        // 3) 回填缓存(含空串),best-effort
        writeCache(cacheKey, summary);
        return summary;
    }

    /** write-through / 回填缓存,best-effort,失败不影响主流程。 */
    private void writeCache(String cacheKey, String summary) {
        try {
            redisTemplate.opsForValue().set(cacheKey, summary, CACHE_TTL);
        } catch (Exception e) {
            log.warn("写摘要缓存失败 key={}: {}", cacheKey, e.getMessage());
        }
    }

    private String renderMessages(List<ChatHistory> list) {
        StringBuilder sb = new StringBuilder();
        for (ChatHistory h : list) {
            boolean isUser = ChatHistoryMessageTypeEnum.USER.getValue().equals(h.getMessageType());
            sb.append(isUser ? "用户:" : "AI:").append(StrUtil.maxLength(h.getMessage(), 4000)).append('\n');
        }
        return sb.toString();
    }

    private void upsert(Long appId, AppMemorySummary current, String summary, long newCursor) {
        int tokens = summary == null ? 0 : summary.length() / 4;
        if (current == null) {
            // 显式补时间戳:BaseMapper.insert 为全列插入,不补则写入 NULL 触发 NOT NULL 约束
            LocalDateTime now = LocalDateTime.now();
            summaryMapper.insert(AppMemorySummary.builder()
                    .appId(appId).summary(summary).lastSummarizedId(newCursor)
                    .summaryTokens(tokens).failCount(0)
                    .createTime(now).updateTime(now).build());
        } else {
            current.setSummary(summary);
            current.setLastSummarizedId(newCursor);
            current.setSummaryTokens(tokens);
            current.setFailCount(0);
            current.setUpdateTime(LocalDateTime.now());
            summaryMapper.update(current);
        }
    }

    private void bumpFail(Long appId, AppMemorySummary current) {
        if (current == null) { // 首次就失败:插一行只记 failCount
            LocalDateTime now = LocalDateTime.now();
            summaryMapper.insert(AppMemorySummary.builder()
                    .appId(appId).summary("").lastSummarizedId(0L).summaryTokens(0).failCount(1)
                    .createTime(now).updateTime(now).build());
        } else {
            current.setFailCount((current.getFailCount() == null ? 0 : current.getFailCount()) + 1);
            current.setUpdateTime(LocalDateTime.now());
            summaryMapper.update(current); // 注意:不改 lastSummarizedId(游标不前进)
        }
    }
}
