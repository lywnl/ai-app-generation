package com.lyw.appgeneration.manger;

import com.lyw.appgeneration.ai.tools.BaseTool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工具管理器
 * 统一管理所有工具，提供根据名称获取工具的功能
 */
@Slf4j
@Component
public class ToolManager {

    /** 已校验的数组和映射通过同一不可变快照原子发布。 */
    private volatile ToolRegistry registry = ToolRegistry.empty();

    /**
     * 自动注入所有工具
     */
    @Resource
    private BaseTool[] tools;

    /**
     * 初始化工具映射
     */
    @PostConstruct
    public void initTools() {
        BaseTool[] candidates = Objects.requireNonNull(tools, "注入工具数组不能为空").clone();
        Map<String, BaseTool> validatedTools = new LinkedHashMap<>();
        for (BaseTool tool : candidates) {
            if (tool == null) {
                throw new IllegalStateException("工具实例不能为空");
            }
            String toolName = tool.getToolName();
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalStateException("工具名称不能为空");
            }
            if (validatedTools.putIfAbsent(toolName, tool) != null) {
                throw new IllegalStateException("存在重复工具名称: " + toolName);
            }
        }
        registry = new ToolRegistry(Map.copyOf(validatedTools), candidates);
        validatedTools.forEach((name, tool) ->
                log.info("注册工具: {} -> {}", name, tool.getDisplayName()));
        log.info("工具管理器初始化完成，共注册 {} 个工具", validatedTools.size());
    }

    /**
     * 根据工具名称获取工具实例
     *
     * @param toolName 工具英文名称
     * @return 工具实例
     */
    public BaseTool getTool(String toolName) {
        return registry.toolMap().get(toolName);
    }

    /**
     * 获取已注册的工具集合
     *
     * @return 工具实例集合
     */
    public BaseTool[] getAllTools() {
        return registry.tools().clone();
    }

    /**
     * 按显式白名单选择工具；未知名称直接拒绝，避免配置错误悄悄扩大或缩小权限。
     */
    public BaseTool[] getTools(Set<String> allowedToolNames) {
        Set<String> allowed = Set.copyOf(
                Objects.requireNonNull(allowedToolNames, "工具白名单不能为空"));
        ToolRegistry currentRegistry = registry;
        Set<String> unknown = new java.util.HashSet<>(allowed);
        unknown.removeAll(currentRegistry.toolMap().keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("工具白名单包含未知工具: " + unknown);
        }
        return java.util.Arrays.stream(currentRegistry.tools())
                .filter(tool -> allowed.contains(tool.getToolName()))
                .toArray(BaseTool[]::new);
    }

    private record ToolRegistry(Map<String, BaseTool> toolMap, BaseTool[] tools) {

        private ToolRegistry {
            toolMap = Map.copyOf(toolMap);
            tools = tools.clone();
        }

        private static ToolRegistry empty() {
            return new ToolRegistry(Map.of(), new BaseTool[0]);
        }
    }
}
