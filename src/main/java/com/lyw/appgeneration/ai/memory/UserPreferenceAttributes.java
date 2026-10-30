package com.lyw.appgeneration.ai.memory;

import cn.hutool.crypto.digest.DigestUtil;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 只规范属性身份，不为偏好的内容建立固定取值目录。 */
public final class UserPreferenceAttributes {
    public static final List<String> CATEGORIES = List.of(
            "语言偏好", "视觉风格", "技术栈倾向", "体验偏好");
    private static final Map<String, Attribute> ALIASES = new LinkedHashMap<>();

    static {
        register("语言偏好", "ui.default_language", "页面默认语言", "默认语言", "界面语言", "应用语言", "页面语言");
        register("语言偏好", "code.comment_language", "代码注释语言", "注释语言");
        register("语言偏好", "docs.language", "文档语言");
        register("视觉风格", "ui.theme", "明暗主题", "主题", "主题模式", "深浅主题");
        register("视觉风格", "ui.visual_style", "视觉样式", "视觉风格", "设计风格");
        register("视觉风格", "ui.primary_color", "主色", "品牌主色", "主题色");
        register("视觉风格", "ui.card_radius", "卡片圆角", "圆角");
        register("视觉风格", "ui.font_size", "正文字号", "字号");
        register("技术栈倾向", "tech.frontend.framework", "前端框架", "前端技术框架");
        register("技术栈倾向", "tech.language", "开发语言", "编程语言");
        register("技术栈倾向", "tech.css_framework", "CSS 框架", "样式框架");
        register("技术栈倾向", "tech.backend.framework", "后端框架");
        register("体验偏好", "ui.motion", "动效偏好", "动画", "动效");
        register("体验偏好", "ui.autoplay", "自动播放", "视频自动播放");
        register("体验偏好", "ui.device_priority", "设备适配优先级", "设备优先级");
        register("体验偏好", "ui.input_method", "输入方式", "操作方式");
        register("体验偏好", "ui.interaction_complexity", "交互复杂度", "交互简洁度");
    }

    private UserPreferenceAttributes() { }

    public static Attribute resolve(String category, String attribute) {
        if (!CATEGORIES.contains(category) || attribute == null
                || attribute.isBlank() || attribute.length() > 128
                || !MemoryTextSafety.isSafe(attribute)) {
            throw new IllegalArgumentException("偏好类别或属性名称无效");
        }
        String normalized = normalizeAttribute(attribute);
        Attribute known = ALIASES.get(category + "\n" + normalized);
        return known != null ? known : new Attribute(
                "custom." + DigestUtil.sha256Hex(category + "\n" + normalized),
                attribute.strip());
    }

    public static String normalizeContent(String content) {
        return content == null ? "" : content.replaceAll("\\s+", " ").strip();
    }

    public static String contentHash(String content) {
        return DigestUtil.sha256Hex(normalizeContent(content));
    }

    private static String normalizeAttribute(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "").strip();
    }

    private static void register(String category, String key, String name, String... aliases) {
        Attribute attribute = new Attribute(key, name);
        ALIASES.put(category + "\n" + normalizeAttribute(key), attribute);
        ALIASES.put(category + "\n" + normalizeAttribute(name), attribute);
        for (String alias : aliases) {
            ALIASES.put(category + "\n" + normalizeAttribute(alias), attribute);
        }
    }

    public record Attribute(String key, String name) { }
}
