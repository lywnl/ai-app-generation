package com.lyw.appgeneration.ai.memory;

import cn.hutool.core.util.StrUtil;

import java.util.List;

/** L1 摘要固定五段格式的唯一契约。 */
public final class MemorySummaryFormat {

    private static final List<Section> SECTIONS = List.of(
            new Section("# 应用目标与定位", "(用户要做什么应用、核心定位)"),
            new Section("# 用户偏好与硬约束",
                    "(明确偏好,以及\"不要X/要Y\"的纠正 —— 含否定项,最重要)"),
            new Section("# 已否决的方案", "(试过但被用户否决的,防止重复犯错)"),
            new Section("# 关键设计决策与理由", "(记理由,不记代码结果)"),
            new Section("# 当前进度速览",
                    "(一两句,指向 vue_project_<appId> 最新代码;不复制代码)"));

    private MemorySummaryFormat() {
    }

    static String sectionTemplate() {
        StringBuilder template = new StringBuilder();
        for (Section section : SECTIONS) {
            template.append(section.heading())
                    .append('\n')
                    .append(section.hint())
                    .append('\n');
        }
        return template.toString().stripTrailing();
    }

    /** 标题必须唯一、顺序固定，且不得增加其他 Markdown 标题。 */
    public static boolean isValid(String summary) {
        if (StrUtil.isBlank(summary)) {
            return false;
        }
        if (!MemoryTextSafety.isSafe(summary)) {
            return false;
        }
        String normalized = summary
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        if (!normalized.startsWith(SECTIONS.getFirst().heading() + "\n")) {
            return false;
        }
        int nextSection = 0;
        boolean currentSectionHasContent = false;
        for (String line : normalized.split("\n", -1)) {
            String visibleLine = line.stripLeading();
            if (isCodeFence(visibleLine)) {
                return false;
            }
            if (isSetextUnderline(visibleLine)) {
                return false;
            }
            if (!isMarkdownHeading(visibleLine)) {
                currentSectionHasContent |= !visibleLine.isBlank();
                continue;
            }
            if (nextSection > 0 && !currentSectionHasContent) {
                return false;
            }
            if (nextSection >= SECTIONS.size()
                    || !SECTIONS.get(nextSection).heading().equals(line)) {
                return false;
            }
            nextSection++;
            currentSectionHasContent = false;
        }
        return nextSection == SECTIONS.size() && currentSectionHasContent;
    }

    private static boolean isCodeFence(String line) {
        return line.startsWith("```") || line.startsWith("~~~");
    }

    private static boolean isSetextUnderline(String line) {
        String trimmed = line.stripTrailing();
        if (trimmed.isEmpty()) {
            return false;
        }
        char marker = trimmed.charAt(0);
        if (marker != '=' && marker != '-') {
            return false;
        }
        for (int index = 1; index < trimmed.length(); index++) {
            if (trimmed.charAt(index) != marker) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMarkdownHeading(String line) {
        int hashCount = 0;
        while (hashCount < line.length()
                && hashCount < 6
                && line.charAt(hashCount) == '#') {
            hashCount++;
        }
        return hashCount > 0
                && hashCount < line.length()
                && Character.isWhitespace(line.charAt(hashCount));
    }

    private record Section(String heading, String hint) {
    }
}
