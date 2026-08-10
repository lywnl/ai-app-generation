package com.lyw.appgeneration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 生产应用上下文会创建模型、MySQL、Redis、PGVector 与对象存储客户端，
 * 因而只在显式提供集成环境时运行，避免默认单元测试连接外部服务。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EXTERNAL_INTEGRATION_TESTS", matches = "true")
class AiAppGenerationApplicationTests {

    @Test
    void contextLoads() {
    }

}
