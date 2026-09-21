package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.plan.AppPlanStateManager;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.ThrowUtils;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.model.vo.app.AppPlanVO;
import com.lyw.appgeneration.service.AppPlanQueryService;
import com.lyw.appgeneration.service.AppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public final class AppPlanQueryServiceImpl implements AppPlanQueryService {
    private final AppService appService;
    private final AppDataLifecycleFence lifecycleFence;
    private final AppPlanStateManager plans;

    public AppPlanQueryServiceImpl(AppService appService, AppDataLifecycleFence lifecycleFence,
                                   AppPlanStateManager plans) {
        this.appService = appService;
        this.lifecycleFence = lifecycleFence;
        this.plans = plans;
    }

    @Override
    public AppPlanVO getCurrentPlan(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null || loginUser.getId() <= 0,
                ErrorCode.NOT_LOGIN_ERROR);
        requireOwner(appId, loginUser);
        var permit = lifecycleFence.tryAcquireWriter(appId);
        ThrowUtils.throwIf(permit == null, ErrorCode.OPERATION_ERROR, "应用正在删除，无法读取计划");
        // 读取也占用短期生命周期许可，避免删除在读取期间移除项目目录。
        try (permit) {
            requireOwner(appId, loginUser);
            return readPlan(appId);
        }
    }

    private void requireOwner(long appId, User loginUser) {
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "无权限查看该应用计划");
        ThrowUtils.throwIf(!CodeGenTypeEnum.VUE_PROJECT.getValue().equals(app.getCodeGenType()),
                ErrorCode.PARAMS_ERROR, "该应用不支持计划查询");
    }

    private AppPlanVO readPlan(long appId) {
        try {
            return plans.loadReadOnly(appId).map(AppPlanVO::from).orElse(null);
        } catch (RuntimeException exception) {
            log.warn("计划查询失败,appId={},errorType={}", appId,
                    exception.getClass().getSimpleName(), exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "计划暂时无法读取，请稍后重试");
        }
    }
}
