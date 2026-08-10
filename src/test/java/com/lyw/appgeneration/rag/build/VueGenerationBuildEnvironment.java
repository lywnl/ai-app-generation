package com.lyw.appgeneration.rag.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 高成本 Vue 真实生成构建评测的无秘密环境前置检查。
 */
public record VueGenerationBuildEnvironment(boolean ready, List<String> reasons) {

    private static final List<String> REQUIRED_CREDENTIALS = List.of(
            "DASHSCOPE_API_KEY",
            "DEEPSEEK_API_KEY",
            "SPRING_DATASOURCE_PASSWORD");

    public VueGenerationBuildEnvironment {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static VueGenerationBuildEnvironment inspectSystemEnvironment() {
        return inspect(System.getenv());
    }

    static VueGenerationBuildEnvironment inspect(Map<String, String> environment) {
        if (!"true".equalsIgnoreCase(environment.get("RAG_BUILD_EVAL"))) {
            return new VueGenerationBuildEnvironment(
                    false, List.of("RAG_BUILD_EVAL 未设置为 true"));
        }

        List<String> reasons = new ArrayList<>();
        REQUIRED_CREDENTIALS.forEach(name -> requireEnvironment(environment, name, reasons));
        return new VueGenerationBuildEnvironment(reasons.isEmpty(), reasons);
    }

    private static void requireEnvironment(
            Map<String, String> environment,
            String name,
            List<String> reasons) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            reasons.add("缺少环境变量 " + name);
        }
    }
}
