package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** 以数据库稳定完整回合为事实源，恢复进程外遗漏的 L2 trigger。 */
@Slf4j
@Component
public final class UserMemoryRecoveryCoordinator {

    static final int PAGE_SIZE = 100;

    private final AppMapper appMapper;
    private final AppMemoryExtractCursorMapper cursorMapper;
    private final ChatHistoryService chatHistoryService;
    private final UserMemoryService userMemoryService;
    private final ExecutorService executor;
    private final AtomicBoolean scanRunning = new AtomicBoolean();

    public UserMemoryRecoveryCoordinator(
            AppMapper appMapper,
            AppMemoryExtractCursorMapper cursorMapper,
            ChatHistoryService chatHistoryService,
            UserMemoryService userMemoryService,
            @Qualifier("userMemoryExtractionExecutor") ExecutorService executor) {
        this.appMapper = Objects.requireNonNull(appMapper, "应用 Mapper 不能为空");
        this.cursorMapper = Objects.requireNonNull(cursorMapper, "游标 Mapper 不能为空");
        this.chatHistoryService = Objects.requireNonNull(
                chatHistoryService, "历史服务不能为空");
        this.userMemoryService = Objects.requireNonNull(
                userMemoryService, "用户记忆服务不能为空");
        this.executor = Objects.requireNonNull(executor, "恢复执行器不能为空");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        requestReconciliation();
    }

    /** 提交 single-flight 异步对账；运行中请求直接合并。 */
    public void requestReconciliation() {
        if (!scanRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.submit(this::runReconciliationSafely);
        } catch (RuntimeException exception) {
            scanRunning.set(false);
            log.warn("提交 L2 数据库对账失败 type={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void runReconciliationSafely() {
        try {
            scanPages();
        } finally {
            scanRunning.set(false);
        }
    }

    private void scanPages() {
        long pageNumber = 1L;
        while (true) {
            Page<App> page;
            try {
                page = appMapper.paginate(
                        Page.of(pageNumber, PAGE_SIZE),
                        QueryWrapper.create().orderBy("id", true));
            } catch (RuntimeException exception) {
                log.warn("L2 对账分页查询失败 page={} type={}",
                        pageNumber, exception.getClass().getSimpleName());
                return;
            }
            if (page == null || page.getRecords() == null) {
                log.warn("L2 对账分页返回空对象 page={}", pageNumber);
                return;
            }
            for (App app : page.getRecords()) {
                reconcileAppSafely(app);
            }
            if (!page.hasNext()) {
                return;
            }
            pageNumber++;
        }
    }

    private void reconcileAppSafely(App app) {
        Long appId = app == null ? null : app.getId();
        Long userId = app == null ? null : app.getUserId();
        if (appId == null || appId <= 0L || userId == null || userId <= 0L) {
            return;
        }
        try {
            List<ChatHistoryService.StableTurnBoundary> boundaries =
                    chatHistoryService.listRecentCompleteTurnBoundaries(appId, 1);
            if (boundaries == null || boundaries.isEmpty()) {
                return;
            }
            long completedThroughId = boundaries.getLast().completedThroughId();
            AppMemoryExtractCursor cursor = cursorMapper.selectOneByQuery(
                    QueryWrapper.create().eq("appId", appId));
            long lastExtractedId = cursor == null
                    || cursor.getLastExtractedId() == null
                    ? 0L : cursor.getLastExtractedId();
            if (completedThroughId > lastExtractedId) {
                userMemoryService.triggerPreferenceExtractionAsync(
                        userId, appId, completedThroughId);
            }
        } catch (RuntimeException exception) {
            log.warn("L2 对账单应用失败 appId={} type={}",
                    appId, exception.getClass().getSimpleName());
        }
    }
}
