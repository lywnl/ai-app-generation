package com.lyw.appgeneration.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.memory.UserPreferencePromptBuilder;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.AppMemory;
import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.UserMemoryService;
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
 * L2 跨 app 用户长期记忆服务实现。
 *
 * <p>抽取:对话结束钩子异步触发,single-flight 按 userId;游标 per-app;模型抽出结构化偏好 JSON,
 * 按 (userId,type,name) 去重 upsert。召回:appId→userId 反查(进程内缓存)→ top-N → Redis 缓存。
 * 失败 best-effort——游标不进、failCount++、circuit breaker(连续 ≥3 暂停)。零竞态:只读 chat_history/app,
 * 只写 app_memory/app_memory_extract_cursor。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Slf4j
@Service
public class UserMemoryServiceImpl implements UserMemoryService {

    private static final String TYPE_USER_PREFERENCE = "USER_PREFERENCE";
    private static final int DEFAULT_MIN_NEW_MESSAGES = 8;
    private static final int DEFAULT_MAX_MESSAGES_PER_RUN = 60;
    private static final int MAX_FAIL = 3;
    private static final int TOP_N = 10;
    private static final String PREF_CACHE_PREFIX = "mem:pref:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final ChatHistoryService chatHistoryService;
    private final AppMemoryMapper appMemoryMapper;
    private final AppMemoryExtractCursorMapper cursorMapper;
    private final AppMapper appMapper;
    private final ChatModel extractionModel;
    private final ExecutorService executor;
    private final StringRedisTemplate redisTemplate;
    private final AppDataLifecycleFence lifecycleFence;
    private final int minNewMessages;
    private final int maxMessagesPerRun;

    /** single-flight:正在抽取的 userId(独立于 L1 的 inFlight(appId))。 */
    private final Set<Long> inFlightUserIds = ConcurrentHashMap.newKeySet();
    /** appId→userId 反查缓存(归属永不变,与 Caffeine service 缓存隔离)。 */
    private final ConcurrentHashMap<Long, Long> appIdToUserId = new ConcurrentHashMap<>();

    @Autowired
    public UserMemoryServiceImpl(ChatHistoryService chatHistoryService,
                                 AppMemoryMapper appMemoryMapper,
                                 AppMemoryExtractCursorMapper cursorMapper,
                                 AppMapper appMapper,
                                 @Qualifier("openAiChatModel") ChatModel extractionModel,
                                 @Qualifier("memorySummarizationExecutor") ExecutorService executor,
                                 StringRedisTemplate redisTemplate,
                                 AppDataLifecycleFence lifecycleFence) {
        this(chatHistoryService, appMemoryMapper, cursorMapper, appMapper, extractionModel, executor,
                redisTemplate, lifecycleFence,
                DEFAULT_MIN_NEW_MESSAGES, DEFAULT_MAX_MESSAGES_PER_RUN);
    }

    /** 全参构造器:显式阈值,供单测注入小阈值聚焦核心逻辑。 */
    UserMemoryServiceImpl(ChatHistoryService chatHistoryService,
                          AppMemoryMapper appMemoryMapper,
                          AppMemoryExtractCursorMapper cursorMapper,
                          AppMapper appMapper,
                          ChatModel extractionModel,
                          ExecutorService executor,
                          StringRedisTemplate redisTemplate,
                          AppDataLifecycleFence lifecycleFence,
                          int minNewMessages,
                          int maxMessagesPerRun) {
        this.chatHistoryService = chatHistoryService;
        this.appMemoryMapper = appMemoryMapper;
        this.cursorMapper = cursorMapper;
        this.appMapper = appMapper;
        this.extractionModel = extractionModel;
        this.executor = executor;
        this.redisTemplate = redisTemplate;
        this.lifecycleFence = lifecycleFence;
        this.minNewMessages = minNewMessages;
        this.maxMessagesPerRun = maxMessagesPerRun;
    }

    @Override
    public void triggerPreferenceExtractionAsync(Long userId, Long appId) {
        if (userId == null || userId <= 0 || appId == null || appId <= 0) {
            return;
        }
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            log.info("偏好抽取任务被应用数据删除门拒绝 userId={} appId={}",
                    userId, appId);
            return;
        }
        if (!inFlightUserIds.add(userId)) {
            writerPermit.close();
            return; // single-flight:同 user 正在抽取则跳过
        }
        try {
            executor.submit(() -> {
                try {
                    extractWithinPermit(userId, appId);
                } finally {
                    inFlightUserIds.remove(userId);
                    writerPermit.close();
                }
            });
        } catch (Exception e) {
            inFlightUserIds.remove(userId);
            writerPermit.close();
            log.warn("提交偏好抽取任务失败 userId={} appId={}: {}", userId, appId, e.getMessage());
        }
    }

    /** 同步抽取一次。best-effort,不抛异常。 */
    public void extractNow(Long userId, Long appId) {
        if (userId == null || userId <= 0 || appId == null || appId <= 0) {
            return;
        }
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            log.info("同步偏好抽取被应用数据删除门拒绝 userId={} appId={}",
                    userId, appId);
            return;
        }
        try (writerPermit) {
            extractWithinPermit(userId, appId);
        }
    }

    private void extractWithinPermit(Long userId, Long appId) {
        try {
            AppMemoryExtractCursor cursor = cursorMapper.selectOneByQuery(
                    QueryWrapper.create().eq("appId", appId));
            if (cursor != null && cursor.getFailCount() != null && cursor.getFailCount() >= MAX_FAIL) {
                log.warn("appId={} L2抽取连续失败 {} 次,circuit breaker 暂停", appId, cursor.getFailCount());
                return;
            }
            long lastId = (cursor == null || cursor.getLastExtractedId() == null) ? 0L : cursor.getLastExtractedId();
            List<ChatHistory> news = chatHistoryService.listMessagesAfterCursor(appId, lastId, maxMessagesPerRun);
            if (CollUtil.isEmpty(news) || news.size() < minNewMessages) {
                return; // 新增不足,跳过
            }

            String existing = renderExistingPreferences(userId);
            String newText = renderMessages(news);
            String prompt = UserPreferencePromptBuilder.build(existing, newText);

            String raw;
            try {
                raw = extractionModel.chat(prompt);
            } catch (Exception modelErr) {
                log.error("L2抽取模型调用失败 userId={} appId={}: {}", userId, appId, modelErr.getMessage());
                bumpFail(userId, appId, cursor);
                return;
            }

            List<JSONObject> items = parsePreferences(raw);
            if (items == null) { // 解析失败:游标不进,记失败
                log.warn("L2抽取输出非合法JSON userId={} appId={},原文前80字:{}", userId, appId,
                        StrUtil.maxLength(raw, 80));
                bumpFail(userId, appId, cursor);
                return;
            }

            for (JSONObject item : items) {
                String name = item.getStr("name");
                String content = item.getStr("content");
                if (StrUtil.isNotBlank(name) && StrUtil.isNotBlank(content)) {
                    upsertPreference(userId, appId, name, content);
                }
            }

            long newCursor = news.get(news.size() - 1).getId();
            advanceCursor(userId, appId, cursor, newCursor);
            invalidateRecallCache(userId); // 偏好已变,失效召回缓存(下次召回重建)
            log.info("L2偏好抽取成功 userId={} appId={},游标 {} -> {},条目 {}",
                    userId, appId, lastId, newCursor, items.size());
        } catch (Exception e) {
            log.error("L2偏好抽取异常 userId={} appId={}: {}", userId, appId, e.getMessage(), e);
        }
    }

    @Override
    public String recallByApp(Long appId) {
        Long userId = resolveUserId(appId);
        if (userId == null) {
            return "";
        }
        String cacheKey = PREF_CACHE_PREFIX + userId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached; // 命中(含空串)
            }
        } catch (Exception e) {
            log.warn("读取偏好缓存失败 userId={}: {}", userId, e.getMessage());
        }
        String text;
        try {
            List<AppMemory> prefs = appMemoryMapper.selectListByQuery(
                    QueryWrapper.create().eq("userId", userId).eq("type", TYPE_USER_PREFERENCE)
                            .orderBy("updateTime", false).limit(TOP_N));
            text = renderPreferenceLines(prefs);
        } catch (Exception e) {
            log.warn("查询用户偏好失败 userId={}: {}", userId, e.getMessage());
            return ""; // 降级:只用 L1+L0
        }
        writeCache(cacheKey, text);
        return text;
    }

    // ---- 内部 ----

    private Long resolveUserId(Long appId) {
        if (appId == null) {
            return null;
        }
        return appIdToUserId.computeIfAbsent(appId, id -> {
            try {
                App app = appMapper.selectOneById(id);
                return app == null ? null : app.getUserId();
            } catch (Exception e) {
                log.warn("反查 userId 失败 appId={}: {}", id, e.getMessage());
                return null; // 不缓存 null,下次重试
            }
        });
    }

    /** 解析 LLM 输出为偏好条目列表;非法 JSON 返回 null(由调用方按降级处理)。 */
    private List<JSONObject> parsePreferences(String raw) {
        String cleaned = stripJsonFence(raw);
        if (StrUtil.isBlank(cleaned)) {
            return List.of();
        }
        try {
            JSONArray arr = JSONUtil.parseArray(cleaned);
            return arr.toList(JSONObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉 LLM 可能包裹的 ```json ... ``` fence。 */
    private String stripJsonFence(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("^```(json|JSON)?", "").replaceAll("```$", "").trim();
        }
        return t;
    }

    private void upsertPreference(Long userId, Long appId, String name, String content) {
        AppMemory existing = appMemoryMapper.selectOneByQuery(QueryWrapper.create()
                .eq("userId", userId).eq("type", TYPE_USER_PREFERENCE).eq("name", name));
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now();
            appMemoryMapper.insert(AppMemory.builder()
                    .userId(userId).appId(appId).type(TYPE_USER_PREFERENCE)
                    .name(name).content(content)
                    .createTime(now).updateTime(now).build());
        } else if (!content.equals(existing.getContent())) {
            existing.setContent(content);
            existing.setUpdateTime(LocalDateTime.now());
            appMemoryMapper.update(existing);
        }
    }

    private void advanceCursor(Long userId, Long appId, AppMemoryExtractCursor cursor, long newCursor) {
        if (cursor == null) {
            LocalDateTime now = LocalDateTime.now();
            cursorMapper.insert(AppMemoryExtractCursor.builder()
                    .appId(appId).userId(userId).lastExtractedId(newCursor).failCount(0)
                    .createTime(now).updateTime(now).build());
        } else {
            cursor.setLastExtractedId(newCursor);
            cursor.setFailCount(0);
            cursor.setUpdateTime(LocalDateTime.now());
            cursorMapper.update(cursor);
        }
    }

    private void bumpFail(Long userId, Long appId, AppMemoryExtractCursor cursor) {
        if (cursor == null) {
            LocalDateTime now = LocalDateTime.now();
            cursorMapper.insert(AppMemoryExtractCursor.builder()
                    .appId(appId).userId(userId).lastExtractedId(0L).failCount(1)
                    .createTime(now).updateTime(now).build());
        } else {
            cursor.setFailCount((cursor.getFailCount() == null ? 0 : cursor.getFailCount()) + 1);
            cursor.setUpdateTime(LocalDateTime.now());
            cursorMapper.update(cursor); // 不改 lastExtractedId(游标不前进)
        }
    }

    private String renderExistingPreferences(Long userId) {
        try {
            List<AppMemory> prefs = appMemoryMapper.selectListByQuery(
                    QueryWrapper.create().eq("userId", userId).eq("type", TYPE_USER_PREFERENCE)
                            .orderBy("updateTime", false).limit(TOP_N));
            return renderPreferenceLines(prefs);
        } catch (Exception e) {
            return ""; // 读已有偏好失败不致命,按无已有处理
        }
    }

    private String renderPreferenceLines(List<AppMemory> prefs) {
        if (CollUtil.isEmpty(prefs)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AppMemory p : prefs) {
            sb.append("- ").append(p.getName()).append(":").append(p.getContent()).append('\n');
        }
        return sb.toString().trim();
    }

    private String renderMessages(List<ChatHistory> list) {
        StringBuilder sb = new StringBuilder();
        for (ChatHistory h : list) {
            boolean isUser = ChatHistoryMessageTypeEnum.USER.getValue().equals(h.getMessageType());
            sb.append(isUser ? "用户:" : "AI:").append(StrUtil.maxLength(h.getMessage(), 4000)).append('\n');
        }
        return sb.toString();
    }

    private void invalidateRecallCache(Long userId) {
        try {
            redisTemplate.delete(PREF_CACHE_PREFIX + userId);
        } catch (Exception e) {
            log.warn("失效偏好缓存失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private void writeCache(String cacheKey, String text) {
        try {
            redisTemplate.opsForValue().set(cacheKey, text, CACHE_TTL);
        } catch (Exception e) {
            log.warn("写偏好缓存失败 key={}: {}", cacheKey, e.getMessage());
        }
    }
}
