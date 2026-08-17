package com.lyw.appgeneration.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/** 在唯一全局周期任务中恢复本地调度，并驱动较低频数据库对账。 */
@Slf4j
@Component
public final class UserMemoryRecoveryWatchdog {

    private static final Duration TICK_INTERVAL = Duration.ofSeconds(5);

    private final TaskScheduler scheduler;
    private final UserMemoryServiceImpl userMemoryService;
    private final UserMemoryRecoveryCoordinator recoveryCoordinator;
    private int ticksUntilDatabaseScan = 1;

    public UserMemoryRecoveryWatchdog(
            @Qualifier("userMemoryRecoveryScheduler") TaskScheduler scheduler,
            UserMemoryServiceImpl userMemoryService,
            UserMemoryRecoveryCoordinator recoveryCoordinator) {
        this.scheduler = Objects.requireNonNull(
                scheduler, "恢复调度器不能为空");
        this.userMemoryService = Objects.requireNonNull(
                userMemoryService, "用户记忆服务不能为空");
        this.recoveryCoordinator = Objects.requireNonNull(
                recoveryCoordinator, "数据库恢复协调器不能为空");
    }

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(this::tick, TICK_INTERVAL);
    }

    void tick() {
        try {
            userMemoryService.recoverUnscheduledPending();
            if (--ticksUntilDatabaseScan <= 0) {
                recoveryCoordinator.requestReconciliation();
                ticksUntilDatabaseScan = 12;
            }
        } catch (RuntimeException exception) {
            log.warn("L2 本地调度恢复 tick 失败 type={}",
                    exception.getClass().getSimpleName());
        }
    }
}
