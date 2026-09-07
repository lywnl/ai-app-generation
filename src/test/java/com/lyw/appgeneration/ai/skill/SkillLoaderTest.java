package com.lyw.appgeneration.ai.skill;

import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLoaderTest {

    private static final String SAMPLE_PATH = "skills/sample/SKILL.md";
    private static final String HEADER = "---\nname: sample\ndescription: 测试元数据\n---\n";

    @Test
    void 元数据结束线恰好在8192字节通过而8193字节拒绝并定位路径() {
        String prefix = "---\nname: sample\ndescription: ";
        String suffix = "\n---\n";
        String exact = prefix + "x".repeat(8192 - prefix.length() - suffix.length()) + suffix;
        assertEquals("sample", loaderWith(exact + "正文")
                .loadMetadataFromClasspath(SAMPLE_PATH).name());
        String overflow = prefix + "x".repeat(8193 - prefix.length() - suffix.length()) + suffix;
        var failure = assertThrows(IllegalArgumentException.class,
                () -> loaderWith(overflow + "正文").loadMetadataFromClasspath(SAMPLE_PATH));
        assertTrue(failure.getMessage().contains(SAMPLE_PATH));
    }

    @Test
    void 大正文不影响元数据读取且不读取正文首字节() {
        byte[] header = HEADER.getBytes(StandardCharsets.UTF_8);
        AtomicInteger reads = new AtomicInteger();
        SkillLoader loader = new SkillLoader(new ClassLoader() {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new InputStream() {
                    @Override
                    public int read() {
                        int index = reads.getAndIncrement();
                        if (index >= header.length) {
                            throw new AssertionError("启动读取到了正文");
                        }
                        return header[index] & 0xff;
                    }
                };
            }
        });
        assertEquals("sample", loader.loadMetadataFromClasspath(SAMPLE_PATH).name());
        assertEquals(header.length, reads.get());
        assertEquals("sample", loaderWith(HEADER + "正文".repeat(5000))
                .loadMetadataFromClasspath(SAMPLE_PATH).name());
    }

    @Test
    void 超限或未闭合元数据和非法编码被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> loaderWith(
                "---\nname: sample\ndescription: " + "x".repeat(8192) + "\n---\n正文")
                .loadMetadataFromClasspath(SAMPLE_PATH));
        assertThrows(IllegalArgumentException.class, () -> loaderWith("---\nname: sample\n")
                .loadMetadataFromClasspath(SAMPLE_PATH));
        assertThrows(IllegalArgumentException.class, () -> loaderWith(new byte[] {
                '-', '-', '-', '\n', (byte) 0xc3, 0x28, '\n', '-', '-', '-', '\n'})
                .loadMetadataFromClasspath(SAMPLE_PATH));
        assertThrows(IllegalArgumentException.class, () -> loaderWith(HEADER + "x".repeat(65536))
                .loadFromClasspath(SAMPLE_PATH));
    }

    @Test
    void 缺失资源异常定位路径() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new SkillLoader().loadMetadataFromClasspath("skills/missing/SKILL.md"));
        assertTrue(failure.getMessage().contains("skills/missing/SKILL.md"));
    }

    private SkillLoader loaderWith(String source) {
        return loaderWith(source.getBytes(StandardCharsets.UTF_8));
    }

    private SkillLoader loaderWith(byte[] bytes) {
        return new SkillLoader(new ClassLoader() {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new ByteArrayInputStream(bytes);
            }
        });
    }

    @Test
    void 元数据加载只返回frontmatter而不包含正文() {
        SkillMetadata metadata = new SkillLoader()
                .loadMetadataFromClasspath("skills/vue-frontend-design/SKILL.md");

        assertEquals("vue-frontend-design", metadata.name());
        assertTrue(metadata.description().contains("Vue 3"));
        assertEquals("skills/vue-frontend-design/SKILL.md", metadata.resourcePath());
        assertFalse(metadata.description().contains("把用户的业务主题"));
    }

    @Test
    void 能读取内置Vue前端设计Skill并保留项目边界() {
        SkillDefinition definition = new SkillLoader()
                .loadFromClasspath("skills/vue-frontend-design/SKILL.md");

        assertEquals("vue-frontend-design", definition.name());
        assertTrue(definition.description().contains("Vue 3"));
        assertTrue(definition.body().contains("本项目边界"));
        assertTrue(definition.body().contains("不增加依赖"));
        assertFalse(definition.body().contains("writeFile"));
    }

    @Test
    void Skill快照内容和消息列表不可变() {
        SkillDefinition definition = new SkillLoader()
                .loadFromClasspath("skills/vue-frontend-design/SKILL.md");

        assertThrows(UnsupportedOperationException.class,
                () -> definition.messages().add(null));
        assertEquals(1, definition.messages().size());
        assertTrue(((SystemMessage) definition.messages().get(0)).text().contains(
                "Vue 前端设计"));
        assertTrue(((SystemMessage) definition.messages().get(0)).text().contains(
                "先判断当前请求是否命中触发说明"));
    }

    @Test
    void 缺少合法frontmatter时拒绝加载() {
        SkillLoader loader = new SkillLoader();

        assertThrows(IllegalArgumentException.class,
                () -> loader.parse("# 没有 frontmatter\n"));
        assertThrows(IllegalArgumentException.class,
                () -> loader.parse("---\nname: bad_name\ndescription: x\n---\n正文"));
    }
}
