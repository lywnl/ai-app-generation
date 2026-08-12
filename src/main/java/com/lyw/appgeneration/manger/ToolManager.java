package com.lyw.appgeneration.manger;

import com.lyw.appgeneration.ai.tools.BaseTool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Comparator;
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

    /**
     * 工具名称到工具实例的映射
     */
    private final Map<String, BaseTool> toolMap = new HashMap<>();

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
        for (BaseTool tool : tools) {
            toolMap.put(tool.getToolName(), tool);
            log.info("注册工具: {} -> {}", tool.getToolName(), tool.getDisplayName());
        }
        log.info("工具管理器初始化完成，共注册 {} 个工具", toolMap.size());
    }

    /**
     * 根据工具名称获取工具实例
     *
     * @param toolName 工具英文名称
     * @return 工具实例
     */
    public BaseTool getTool(String toolName) {
        return toolMap.get(toolName);
    }

    /**
     * 获取已注册的工具集合
     *
     * @return 工具实例集合
     */
    public BaseTool[] getAllTools() {
        return tools;
    }

    /**
     * 按显式白名单选择工具；未知名称直接拒绝，避免配置错误悄悄扩大或缩小权限。
     */
    public BaseTool[] getTools(Set<String> allowedToolNames) {
        Set<String> allowed = Set.copyOf(
                Objects.requireNonNull(allowedToolNames, "工具白名单不能为空"));
        Set<String> unknown = new java.util.HashSet<>(allowed);
        unknown.removeAll(toolMap.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("工具白名单包含未知工具: " + unknown);
        }
        return toolMap.values().stream()
                .filter(tool -> allowed.contains(tool.getToolName()))
                .sorted(Comparator.comparingInt(tool -> indexOfTool(tool.getToolName())))
                .toArray(BaseTool[]::new);
    }

    private int indexOfTool(String toolName) {
        for (int index = 0; index < tools.length; index++) {
            if (tools[index].getToolName().equals(toolName)) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }
}
