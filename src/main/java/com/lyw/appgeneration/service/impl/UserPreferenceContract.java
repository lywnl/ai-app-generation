package com.lyw.appgeneration.service.impl;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.MemoryTextSafety;
import com.lyw.appgeneration.ai.memory.UserPreferenceValueCatalog;
import com.lyw.appgeneration.ai.memory.UserPreferenceMessageFragmentBuilder;
import com.lyw.appgeneration.config.MemoryTokenProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 统一封闭模型输出、历史偏好和召回缓存使用的 L2 偏好契约。 */
final class UserPreferenceContract {

    static final int MAX_CANDIDATES = 5;

    private static final List<String> ALLOWED_NAMES =
            UserPreferenceValueCatalog.categoryNames();
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

    List<String> allowedNames() {
        return ALLOWED_NAMES;
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
                UserPreferenceCandidateParser.normalizeContent(content);
        return isAllowedName(name)
                && MemoryTextSafety.isSafe(content)
                && StrUtil.isNotBlank(normalizedContent)
                && content.equals(normalizedContent)
                && UserPreferenceValueCatalog.isCanonical(
                StrUtil.trim(name), content)
                && fragmentBuilder.isWithinBudget(
                renderPreferenceLine(name, normalizedContent));
    }

    String renderPreferenceLine(String name, String content) {
        return "- " + StrUtil.trim(name) + ":"
                + UserPreferenceCandidateParser.normalizeContent(content);
    }

    int estimatePreferenceLine(String name, String content) {
        return tokenEstimator.estimateText(
                renderPreferenceLine(name, content));
    }

    boolean isValidRecallCache(String cached) {
        if (cached == null || !fragmentBuilder.isWithinBudget(cached)) {
            return false;
        }
        if (cached.isEmpty()) {
            return true;
        }
        Set<String> names = new HashSet<>();
        for (String line : cached.split("\\n", -1)) {
            String name = resolveCachedLineName(line);
            if (name == null || !names.add(name)) {
                return false;
            }
        }
        return names.size() <= MAX_CANDIDATES;
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
