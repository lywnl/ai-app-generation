package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserMemoryRecoveryCoordinatorTest {

    @Test
    @DisplayName("启动扫描异步分页恢复无游标的首个稳定回合")
    void startupReconciliationRecoversFirstStableTurnAsynchronously() {
        AppMapper appMapper = mock(AppMapper.class);
        AppMemoryExtractCursorMapper cursorMapper =
                mock(AppMemoryExtractCursorMapper.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        UserMemoryService memoryService = mock(UserMemoryService.class);
        QueuedExecutor executor = new QueuedExecutor();
        Page<App> page = new Page<>(List.of(
                App.builder().id(10L).userId(7L).build()), 1, 100, 1);
        when(appMapper.paginate(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(page);
        when(historyService.listRecentCompleteTurnBoundaries(10L, 1))
                .thenReturn(List.of(new ChatHistoryService.StableTurnBoundary(
                        11L, 12L, "用户", "AI")));
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        UserMemoryRecoveryCoordinator coordinator =
                new UserMemoryRecoveryCoordinator(
                        appMapper, cursorMapper, historyService,
                        memoryService, executor);

        coordinator.requestReconciliation();

        verify(memoryService, never()).triggerPreferenceExtractionAsync(
                any(), any(), any());
        executor.runAll();
        verify(memoryService).triggerPreferenceExtractionAsync(7L, 10L, 12L);
    }

    @Test
    @DisplayName("单个应用异常不阻塞同页其他应用且追平游标不登记")
    void perAppFailureIsIsolatedAndCaughtUpCursorIsSkipped() {
        AppMapper appMapper = mock(AppMapper.class);
        AppMemoryExtractCursorMapper cursorMapper =
                mock(AppMemoryExtractCursorMapper.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        UserMemoryService memoryService = mock(UserMemoryService.class);
        QueuedExecutor executor = new QueuedExecutor();
        when(appMapper.paginate(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(
                        App.builder().id(10L).userId(7L).build(),
                        App.builder().id(20L).userId(8L).build(),
                        App.builder().id(30L).userId(9L).build()),
                        1, 100, 3));
        when(historyService.listRecentCompleteTurnBoundaries(10L, 1))
                .thenThrow(new IllegalStateException("单 app 查询失败"));
        when(historyService.listRecentCompleteTurnBoundaries(20L, 1))
                .thenReturn(List.of(new ChatHistoryService.StableTurnBoundary(
                        21L, 22L, "用户", "AI")));
        when(historyService.listRecentCompleteTurnBoundaries(30L, 1))
                .thenReturn(List.of(new ChatHistoryService.StableTurnBoundary(
                        31L, 32L, "用户", "AI")));
        when(cursorMapper.selectOneByQuery(any()))
                .thenReturn(null, AppMemoryExtractCursor.builder()
                        .lastExtractedId(32L).build());
        UserMemoryRecoveryCoordinator coordinator =
                new UserMemoryRecoveryCoordinator(
                        appMapper, cursorMapper, historyService,
                        memoryService, executor);

        coordinator.requestReconciliation();
        executor.runAll();

        verify(memoryService).triggerPreferenceExtractionAsync(8L, 20L, 22L);
        verify(memoryService, never()).triggerPreferenceExtractionAsync(
                eq(9L), eq(30L), anyLong());
    }

    @Test
    @DisplayName("周期重入时数据库扫描保持 single-flight")
    void reconciliationIsSingleFlight() {
        AppMapper appMapper = mock(AppMapper.class);
        QueuedExecutor executor = new QueuedExecutor();
        UserMemoryRecoveryCoordinator coordinator =
                new UserMemoryRecoveryCoordinator(
                        appMapper, mock(AppMemoryExtractCursorMapper.class),
                        mock(ChatHistoryService.class),
                        mock(UserMemoryService.class), executor);

        coordinator.requestReconciliation();
        coordinator.requestReconciliation();

        assertEquals(1, executor.queuedTaskCount());
        when(appMapper.paginate(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(), 1, 100, 0));
        executor.runAll();
        coordinator.requestReconciliation();
        assertEquals(1, executor.queuedTaskCount());
    }

    @Test
    @DisplayName("超过一百个应用时按页继续扫描")
    void reconciliationScansMultiplePages() {
        AppMapper appMapper = mock(AppMapper.class);
        AppMemoryExtractCursorMapper cursorMapper =
                mock(AppMemoryExtractCursorMapper.class);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        UserMemoryService memoryService = mock(UserMemoryService.class);
        QueuedExecutor executor = new QueuedExecutor();
        Page<App> first = new Page<>(List.of(
                App.builder().id(10L).userId(7L).build()), 1, 100, 101);
        Page<App> second = new Page<>(List.of(
                App.builder().id(20L).userId(8L).build()), 2, 100, 101);
        when(appMapper.paginate(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(first, second);
        when(historyService.listRecentCompleteTurnBoundaries(anyLong(), eq(1)))
                .thenReturn(List.of(new ChatHistoryService.StableTurnBoundary(
                        11L, 12L, "用户", "AI")))
                .thenReturn(List.of(new ChatHistoryService.StableTurnBoundary(
                        21L, 22L, "用户", "AI")));
        when(cursorMapper.selectOneByQuery(any())).thenReturn(null);
        UserMemoryRecoveryCoordinator coordinator =
                new UserMemoryRecoveryCoordinator(
                        appMapper, cursorMapper, historyService,
                        memoryService, executor);

        coordinator.requestReconciliation();
        executor.runAll();

        verify(appMapper, times(2)).paginate(
                any(Page.class), any(QueryWrapper.class));
        verify(memoryService).triggerPreferenceExtractionAsync(7L, 10L, 12L);
        verify(memoryService).triggerPreferenceExtractionAsync(8L, 20L, 22L);
    }

    private static final class QueuedExecutor extends AbstractExecutorService {

        private final ArrayBlockingQueue<Runnable> tasks =
                new ArrayBlockingQueue<>(4);
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = List.copyOf(tasks);
            tasks.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int queuedTaskCount() {
            return tasks.size();
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }
}
