package com.lyw.appgeneration.ai;

import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EXTERNAL_INTEGRATION_TESTS", matches = "true")
public class AiCodeGenTypeRoutingServiceTest {

    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    @Test
    public void testRouteCodeGenType() {
        String userPrompt = "做一个简单的个人介绍页面";
        CodeGenTypeEnum result = aiCodeGenTypeRoutingService.routeCodeGenType(userPrompt);
        log.info("用户需求: {} -> {}", userPrompt, result.getValue());
        assertEquals(CodeGenTypeEnum.HTML, result);

        userPrompt = "做一个单页计算器，要求 index.html、style.css、script.js 三文件分离";
        result = aiCodeGenTypeRoutingService.routeCodeGenType(userPrompt);
        log.info("用户需求: {} -> {}", userPrompt, result.getValue());
        assertEquals(CodeGenTypeEnum.MULTI_FILE, result);

        userPrompt = "做一个公司官网，需要首页、关于我们、联系我们三个页面";
        result = aiCodeGenTypeRoutingService.routeCodeGenType(userPrompt);
        log.info("用户需求: {} -> {}", userPrompt, result.getValue());
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, result);

        userPrompt = "做一个电商管理系统，包含用户管理、商品管理、订单管理，需要路由和状态管理";
        result = aiCodeGenTypeRoutingService.routeCodeGenType(userPrompt);
        log.info("用户需求: {} -> {}", userPrompt, result.getValue());
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, result);
    }
}
