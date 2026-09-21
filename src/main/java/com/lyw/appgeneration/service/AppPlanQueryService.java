package com.lyw.appgeneration.service;

import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.vo.app.AppPlanVO;

/** 查询当前计划，计划不存在时返回 null，不产生回合或文件变更。 */
public interface AppPlanQueryService {
    AppPlanVO getCurrentPlan(Long appId, User loginUser);
}
