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

/** 从 classpath 加载并校验内置 SKILL.md；不读取外部路径或远程资源。 */
public final class SkillLoader {

    private static final int MAX_SKILL_BYTES = 64 * 1024;
    private static final int MAX_FRONTMATTER_BYTES = 8 * 1024;
    public SkillDefinition loadFromClasspath(String resourcePath) {
        validateResourcePath(resourcePath);
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
            validateDirectoryName(resourcePath, definition.name());
            return definition;
        } catch (IOException exception) {
            throw new UncheckedIOException("读取 Skill 资源失败：" + resourcePath,
                    exception);
        }
    }

    /** 只读取有界 frontmatter，启动注册流程不得加载正文。 */
    public SkillMetadata loadMetadataFromClasspath(String resourcePath) {
        validateResourcePath(resourcePath);
        try (InputStream input = SkillLoader.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("Skill 资源不存在：" + resourcePath);
            }
            byte[] bytes = input.readNBytes(MAX_FRONTMATTER_BYTES + 1);
            if (bytes.length > MAX_FRONTMATTER_BYTES) {
                throw new IllegalArgumentException("Skill frontmatter 超过大小上限");
            }
            String source = decodeUtf8(bytes);
            int end = source.indexOf("\n---\n", 4);
            if (!source.startsWith("---\n") || end < 0) {
                throw new IllegalArgumentException("Skill frontmatter 不完整");
            }
            SkillMetadata metadata = parseMetadata(source.substring(4, end), resourcePath);
            validateDirectoryName(resourcePath, metadata.name());
            return metadata;
        } catch (IOException exception) {
            throw new UncheckedIOException("读取 Skill 元数据失败：" + resourcePath,
                    exception);
        }
    }

    public SkillDefinition loadDefinitionFromClasspath(SkillMetadata metadata) {
        Objects.requireNonNull(metadata, "Skill 元数据不能为空");
        SkillDefinition definition = loadFromClasspath(metadata.resourcePath());
        if (!metadata.name().equals(definition.name())
                || !metadata.description().equals(definition.description())) {
            throw new IllegalArgumentException("Skill 元数据与正文不一致：" + metadata.name());
        }
        return definition;
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
        SkillMetadata metadata = parseMetadata(frontmatter, null);
        if (body.isBlank()) {
            throw new IllegalArgumentException("Skill 正文不能为空");
        }
        return SkillDefinition.of(metadata.name(), metadata.description(), body);
    }

    private SkillMetadata parseMetadata(String frontmatter, String resourcePath) {
        String name = null;
        String description = null;
        List<String> descriptionLines = new ArrayList<>();
        boolean collectingDescription = false;
        for (String line : frontmatter.split("\\R", -1)) {
            if (line.startsWith("name:")) {
                name = value(line.substring("name:".length()));
                collectingDescription = false;
            } else if (line.startsWith("description:")) {
                String raw = line.substring("description:".length()).strip();
                collectingDescription = raw.equals(">") || raw.equals("|");
                if (!collectingDescription) {
                    description = value(raw);
                }
            } else if (collectingDescription && !line.isBlank()) {
                descriptionLines.add(line.strip());
            }
        }
        if (description == null && !descriptionLines.isEmpty()) {
            description = String.join(" ", descriptionLines);
        }
        String path = resourcePath == null ? "skills/" + name + "/SKILL.md" : resourcePath;
        return new SkillMetadata(name, description, path);
    }

    private void validateResourcePath(String resourcePath) {
        Objects.requireNonNull(resourcePath, "Skill 资源路径不能为空");
        if (!resourcePath.endsWith("/SKILL.md") || resourcePath.startsWith("/")
                || resourcePath.contains("\\") || resourcePath.contains("..")) {
            throw new IllegalArgumentException("Skill 资源路径不合法：" + resourcePath);
        }
    }

    private void validateDirectoryName(String resourcePath, String name) {
        if (!skillDirectoryName(resourcePath).equals(name)) {
            throw new IllegalArgumentException("Skill name 必须与目录名一致："
                    + skillDirectoryName(resourcePath));
        }
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
