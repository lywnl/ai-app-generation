package com.lyw.appgeneration.service.rag.model;

import java.util.Map;

/** 本轮安全读取到的工程约束；未知工程不视为空工程。 */
public record CurrentVueProject(Status status, Map<String, String> dependencies,
                                Map<String, String> devDependencies, String reason) {
    public CurrentVueProject {
        dependencies = Map.copyOf(dependencies);
        devDependencies = Map.copyOf(devDependencies);
    }

    public static CurrentVueProject unavailable(String reason) {
        return new CurrentVueProject(Status.UNAVAILABLE, Map.of(), Map.of(), reason);
    }

    public boolean available() { return status == Status.AVAILABLE; }
    public boolean existing() { return status != Status.ABSENT; }

    public TemplateDoc compatibilityDocument() {
        if (!available()) throw new IllegalStateException("工程兼容信息不可用");
        TemplateDoc document = new TemplateDoc();
        document.setFramework("vue@" + dependencies.get("vue"));
        document.setBuildTool("vite@" + devDependencies.get("vite"));
        document.setLanguage("javascript");
        document.setDependencies(dependencies);
        document.setDevDependencies(devDependencies);
        return document;
    }

    public enum Status { ABSENT, AVAILABLE, UNAVAILABLE }
}
