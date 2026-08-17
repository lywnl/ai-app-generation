package com.lyw.appgeneration.ai.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * L2 用户偏好的服务端闭合值目录。
 *
 * <p>模型只选择代码，持久化和召回只使用本目录按固定顺序渲染的中文文本。
 */
public final class UserPreferenceValueCatalog {

    public static final int MAX_CODES_PER_CATEGORY = 3;

    private static final Map<String, LinkedHashMap<String, String>> VALUES =
            createValues();
    private static final List<String> CATEGORY_NAMES = List.of(
            "语言偏好", "视觉风格", "技术栈倾向", "交互习惯", "其他");

    private UserPreferenceValueCatalog() {
    }

    /** 返回含暂无代码类别的固定类别顺序。 */
    public static List<String> categoryNames() {
        return CATEGORY_NAMES;
    }

    /** 返回给模型展示的代码目录，不暴露可变内部结构。 */
    public static Map<String, Map<String, String>> valuesByCategory() {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        CATEGORY_NAMES.forEach(name -> copy.put(
                name, Map.copyOf(VALUES.getOrDefault(name, new LinkedHashMap<>()))));
        return Map.copyOf(copy);
    }

    /**
     * 校验代码数组并按目录顺序去重渲染；任一代码非法时返回空。
     */
    public static String render(String category, List<String> valueCodes) {
        LinkedHashMap<String, String> allowed = VALUES.get(category);
        if (allowed == null || allowed.isEmpty() || valueCodes == null
                || valueCodes.isEmpty()) {
            return null;
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String code : valueCodes) {
            if (code == null || !allowed.containsKey(code)) {
                return null;
            }
            requested.add(code);
        }
        if (requested.size() > MAX_CODES_PER_CATEGORY) {
            return null;
        }
        List<String> rendered = new ArrayList<>(requested.size());
        allowed.forEach((code, text) -> {
            if (requested.contains(code)) {
                rendered.add(text);
            }
        });
        return rendered.isEmpty() ? null : String.join("、", rendered);
    }

    /** 旧值只有能由本类别代码唯一规范渲染且文本完全相同时才兼容。 */
    public static boolean isCanonical(String category, String content) {
        if (content == null) {
            return false;
        }
        LinkedHashMap<String, String> allowed = VALUES.get(category);
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        List<String> matchedCodes = allowed.entrySet().stream()
                .filter(entry -> containsCanonicalPart(content, entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        return matchedCodes.size() <= MAX_CODES_PER_CATEGORY
                && Objects.equals(content, render(category, matchedCodes));
    }

    private static boolean containsCanonicalPart(String content, String text) {
        return List.of(content.split("、", -1)).contains(text);
    }

    private static Map<String, LinkedHashMap<String, String>> createValues() {
        Map<String, LinkedHashMap<String, String>> values = new LinkedHashMap<>();
        values.put("语言偏好", entries(
                "ZH_CN", "简体中文",
                "ZH_TW", "繁体中文",
                "EN", "英文",
                "ZH_EN", "中英双语"));
        values.put("视觉风格", entries(
                "DARK", "深色",
                "LIGHT", "浅色",
                "MINIMAL", "极简",
                "FLAT", "扁平化"));
        values.put("技术栈倾向", entries(
                "VUE3", "Vue 3",
                "REACT", "React",
                "TYPESCRIPT", "TypeScript",
                "JAVASCRIPT", "JavaScript",
                "TAILWIND_CSS", "Tailwind CSS",
                "SPRING_BOOT", "Spring Boot"));
        values.put("交互习惯", entries(
                "KEYBOARD_FIRST", "键盘优先",
                "MOBILE_FIRST", "移动端优先",
                "DESKTOP_FIRST", "桌面端优先",
                "SIMPLE_INTERACTION", "简洁交互",
                "REDUCED_MOTION", "减少动效"));
        values.put("其他", new LinkedHashMap<>());
        return Map.copyOf(values);
    }

    private static LinkedHashMap<String, String> entries(String... pairs) {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            entries.put(pairs[index], pairs[index + 1]);
        }
        return entries;
    }
}
