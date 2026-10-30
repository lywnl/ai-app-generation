package com.lyw.appgeneration.service.impl;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.MemoryTextSafety;
import com.lyw.appgeneration.ai.memory.UserPreferenceAttributes;
import com.lyw.appgeneration.ai.memory.UserPreferenceMessageFragmentBuilder;
import com.lyw.appgeneration.config.MemoryTokenProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 固定类别与自由内容的 L2 契约；候选不进入正式召回缓存。 */
final class UserPreferenceContract {

    private static final List<String> ALLOWED_NAMES =
            UserPreferenceAttributes.CATEGORIES;
    private static final Set<String> ALLOWED_NAME_SET =
            Set.copyOf(ALLOWED_NAMES);

    private final ChatTokenEstimator tokenEstimator;
    private final MemoryTokenProperties tokenProperties;
    private final UserPreferenceMessageFragmentBuilder fragmentBuilder;

    UserPreferenceContract(ChatTokenEstimator tokenEstimator,
                           MemoryTokenProperties tokenProperties) {
        this.tokenEstimator = Objects.requireNonNull(
                tokenEstimator, "Token 估算器不能为空");
        this.tokenProperties = Objects.requireNonNull(
                tokenProperties, "Token 配置不能为空");
        this.fragmentBuilder = new UserPreferenceMessageFragmentBuilder(
                this.tokenEstimator, this.tokenProperties);
    }

    boolean isAllowedName(String name) {
        return ALLOWED_NAME_SET.contains(StrUtil.trim(name));
    }

    boolean isRawOutputWithinBudget(String raw) {
        return tokenEstimator.estimateText(raw)
                <= tokenProperties.getMaxOutputTokens();
    }

    boolean isPreferenceWithinBudget(String name, String content) {
        String normalizedContent =
                UserPreferenceAttributes.normalizeContent(content);
        return isAllowedName(name)
                && MemoryTextSafety.isSafe(content)
                && StrUtil.isNotBlank(normalizedContent)
                && content.equals(normalizedContent)
                && fragmentBuilder.isWithinBudget(
                renderPreferenceLine(name, normalizedContent));
    }

    String renderPreferenceLine(String name, String content) {
        return "- " + StrUtil.trim(name) + ":"
                + UserPreferenceAttributes.normalizeContent(content);
    }

    String renderPreferenceLine(String name, String attribute, String content) {
        return renderPreferenceLine(name, attribute + "：" + content);
    }

    boolean isPreferenceWithinBudget(String name, String attribute, String content) {
        return attribute != null && !attribute.isBlank() && attribute.length() <= 128
                && MemoryTextSafety.isSafe(attribute)
                && isPreferenceWithinBudget(name, content)
                && fragmentBuilder.isWithinBudget(renderPreferenceLine(name, attribute, content));
    }

    boolean isValidRecallCache(String cached) {
        if (cached == null || !fragmentBuilder.isWithinBudget(cached)) {
            return false;
        }
        if (cached.isEmpty()) {
            return true;
        }
        Set<String> lines = new HashSet<>();
        for (String line : cached.split("\\n", -1)) {
            String name = resolveCachedLineName(line);
            if (name == null || !lines.add(line)) {
                return false;
            }
        }
        return true;
    }

    private String resolveCachedLineName(String line) {
        for (String name : ALLOWED_NAMES) {
            String prefix = "- " + name + ":";
            if (!line.startsWith(prefix)) {
                continue;
            }
            String content = line.substring(prefix.length());
            if (!isPreferenceWithinBudget(name, content)
                    || !line.equals(renderPreferenceLine(name, content))) {
                return null;
            }
            return name;
        }
        return null;
    }
}
