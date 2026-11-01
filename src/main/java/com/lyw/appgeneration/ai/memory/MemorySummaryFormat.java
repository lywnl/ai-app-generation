package com.lyw.appgeneration.ai.memory;

import cn.hutool.core.util.StrUtil;

import java.util.List;

/** L1 摘要固定五段格式的唯一契约。 */
public final class MemorySummaryFormat {

    private static final List<Section> SECTIONS = List.of(
            new Section("# 应用目标与定位", "(用户要做什么应用、核心定位)"),
            new Section("# 用户偏好与硬约束",
                    "(区分用户明确要求与项目或 Skill 规则，保留有效约束、纠正和否定项)"),
            new Section("# 已否决的方案", "(仅用户明确否决及替代关系；工具失败属于进度问题)"),
            new Section("# 关键设计决策与理由", "(区分用户决定与模型方案及其实施状态；理由只取原始表达)"),
            new Section("# 当前进度速览",
                    "(注明回合和覆盖截止；已完成仅列累计成功写入的全部文件相对路径并去重，不写功能描述或续做接口；保留已有待办、验证状态与有来源的问题，结束原因与构建状态分开，不自行诊断或列历史读取)"));

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
