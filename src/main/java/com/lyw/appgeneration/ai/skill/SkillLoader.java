package com.lyw.appgeneration.ai.skill;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 从 classpath 加载并校验内置 SKILL.md；不读取外部路径或远程资源。 */
public final class SkillLoader {

    private static final int MAX_SKILL_BYTES = 64 * 1024;
    private static final Pattern VALID_NAME =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    public SkillDefinition loadFromClasspath(String resourcePath) {
        Objects.requireNonNull(resourcePath, "Skill 资源路径不能为空");
        if (!resourcePath.endsWith("/SKILL.md")) {
            throw new IllegalArgumentException(
                    "Skill 资源必须指向 SKILL.md：" + resourcePath);
        }
        try (InputStream input = SkillLoader.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "Skill 资源不存在：" + resourcePath);
            }
            // 多读一个字节即可判定超限，避免对异常大的 classpath 资源无界分配。
            byte[] bytes = input.readNBytes(MAX_SKILL_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_SKILL_BYTES) {
                throw new IllegalArgumentException("Skill 文件大小不合法");
            }
            SkillDefinition definition = parse(
                    decodeUtf8(bytes));
            String directoryName = skillDirectoryName(resourcePath);
            if (!directoryName.equals(definition.name())) {
                throw new IllegalArgumentException(
                        "Skill name 必须与目录名一致：" + directoryName);
            }
            return definition;
        } catch (IOException exception) {
            throw new UncheckedIOException("读取 Skill 资源失败：" + resourcePath,
                    exception);
        }
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Skill 文件不是合法 UTF-8", exception);
        }
    }

    private String skillDirectoryName(String resourcePath) {
        int fileSeparator = resourcePath.lastIndexOf('/');
        int directorySeparator = resourcePath.lastIndexOf(
                '/', fileSeparator - 1);
        if (fileSeparator <= 0 || directorySeparator < 0) {
            throw new IllegalArgumentException(
                    "Skill 资源路径必须使用 <目录>/SKILL.md 结构");
        }
        return resourcePath.substring(directorySeparator + 1, fileSeparator);
    }

    SkillDefinition parse(String source) {
        Objects.requireNonNull(source, "Skill 内容不能为空");
        if (!source.startsWith("---\n")) {
            throw new IllegalArgumentException("Skill 必须以 YAML frontmatter 开始");
        }
        int end = source.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalArgumentException("Skill 缺少 YAML frontmatter 结束线");
        }
        String frontmatter = source.substring(4, end);
        String body = source.substring(end + 5).strip();
        String name = null;
        String description = null;
        List<String> descriptionLines = new ArrayList<>();
        boolean collectingDescription = false;
        for (String line : frontmatter.split("\\R", -1)) {
            if (line.startsWith("name:")) {
                name = value(line.substring("name:".length()));
                collectingDescription = false;
            } else if (line.startsWith("description:")) {
                String value = line.substring("description:".length()).strip();
                collectingDescription = value.equals(">")
                        || value.equals("|");
                if (!collectingDescription) {
                    description = value(value);
                }
            } else if (collectingDescription && !line.isBlank()) {
                descriptionLines.add(line.strip());
            }
        }
        if (description == null && !descriptionLines.isEmpty()) {
            description = String.join(" ", descriptionLines);
        }
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Skill name 不合法：" + name);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Skill description 不能为空");
        }
        if (body.isBlank()) {
            throw new IllegalArgumentException("Skill 正文不能为空");
        }
        return SkillDefinition.of(name, description, body);
    }

    private String value(String raw) {
        String result = raw.strip();
        if ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'"))) {
            return result.substring(1, result.length() - 1);
        }
        return result;
    }
}
