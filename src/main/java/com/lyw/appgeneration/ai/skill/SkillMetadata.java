package com.lyw.appgeneration.ai.skill;

import java.util.Objects;
import java.util.regex.Pattern;

/** 内置 Skill 的轻量启动元数据，不包含正文。 */
public record SkillMetadata(String name, String description, String resourcePath) {

    private static final Pattern VALID_NAME =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    public SkillMetadata {
        name = requireValidName(name);
        description = requireNonBlank(description, "Skill 描述不能为空");
        resourcePath = requireResourcePath(resourcePath);
    }

    private static String requireValidName(String value) {
        if (value == null || !VALID_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Skill name 不合法：" + value);
        }
        return value;
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static String requireResourcePath(String value) {
        String path = requireNonBlank(value, "Skill 资源路径不能为空");
        if (!path.endsWith("/SKILL.md") || path.startsWith("/")
                || path.contains("\\") || path.contains("..")) {
            throw new IllegalArgumentException("Skill 资源路径不合法：" + value);
        }
        return path;
    }
}
