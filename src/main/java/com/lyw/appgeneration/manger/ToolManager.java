package com.lyw.appgeneration.manger;

import com.lyw.appgeneration.ai.tools.BaseTool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        registry = new ToolRegistry(validatedTools);
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

    /** 按调用方声明顺序选择工具；空白、重复或未知名称均直接拒绝。 */
    public BaseTool[] requireTools(String... toolNames) {
        Objects.requireNonNull(toolNames, "工具名称数组不能为空");
        if (toolNames.length == 0) {
            throw new IllegalArgumentException("工具名称数组不能为空");
        }
        ToolRegistry currentRegistry = registry;
        Set<String> requested = new LinkedHashSet<>();
        BaseTool[] selected = new BaseTool[toolNames.length];
        for (int index = 0; index < toolNames.length; index++) {
            String toolName = toolNames[index];
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("工具名称不能为空");
            }
            if (!requested.add(toolName)) {
                throw new IllegalArgumentException("工具白名单包含重复工具: " + toolName);
            }
            BaseTool tool = currentRegistry.toolMap().get(toolName);
            if (tool == null) {
                throw new IllegalArgumentException("工具白名单包含未知工具: " + toolName);
            }
            selected[index] = tool;
        }
        return selected;
    }

    private record ToolRegistry(Map<String, BaseTool> toolMap) {

        private ToolRegistry {
            toolMap = Map.copyOf(toolMap);
        }

        private static ToolRegistry empty() {
            return new ToolRegistry(Map.of());
        }
    }
}
