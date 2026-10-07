package com.lyw.appgeneration.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** 在创建 Bean 前校验共享密码；不修剪输入，也不在错误中输出密码。 */
public final class SharedPasswordInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String password = context.getEnvironment().getProperty("INFRA_SHARED_PASSWORD");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("INFRA_SHARED_PASSWORD 未填写，请设置中间件共享密码");
        }
        if (!password.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalStateException(
                    "INFRA_SHARED_PASSWORD 仅允许英文字母、数字和 _-.，不允许空格、中文或其他特殊字符");
        }
        if (password.length() < 8) {
            throw new IllegalStateException(
                    "INFRA_SHARED_PASSWORD 至少需要 8 位，以满足 MinIO 的密码长度要求");
        }
    }
}
