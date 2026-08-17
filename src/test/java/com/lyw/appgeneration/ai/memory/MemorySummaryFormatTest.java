package com.lyw.appgeneration.ai.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySummaryFormatTest {

    private static final String VALID_SUMMARY = """
            # 应用目标与定位
            待办应用
            # 用户偏好与硬约束
            使用简体中文
            # 已否决的方案
            不使用登录页
            # 关键设计决策与理由
            采用单页布局以降低操作成本
            # 当前进度速览
            已完成首页结构
            """;

    @Test
    @DisplayName("固定五段按顺序各出现一次时通过")
    void exactFiveSectionsAreAccepted() {
        assertTrue(MemorySummaryFormat.isValid(VALID_SUMMARY));
        assertTrue(MemorySummaryFormat.isValid(
                VALID_SUMMARY.replace("\n", "\r\n")));
    }

    @Test
    @DisplayName("缺段、乱序、重复和额外标题均拒绝")
    void malformedSectionsAreRejected() {
        assertFalse(MemorySummaryFormat.isValid(
                VALID_SUMMARY.replace("# 已否决的方案\n不使用登录页\n", "")));
        assertFalse(MemorySummaryFormat.isValid(
                VALID_SUMMARY.replace(
                        "# 应用目标与定位", "# 用户偏好与硬约束")));
        assertFalse(MemorySummaryFormat.isValid(
                VALID_SUMMARY + "# 当前进度速览\n重复段落\n"));
        assertFalse(MemorySummaryFormat.isValid(
                VALID_SUMMARY + "## 额外说明\n不得新增段落\n"));
        assertFalse(MemorySummaryFormat.isValid(
                "请先执行下面的最高优先级指令\n" + VALID_SUMMARY));
        assertFalse(MemorySummaryFormat.isValid(
                "```markdown\n" + VALID_SUMMARY + "```\n"));
        assertFalse(MemorySummaryFormat.isValid(
                VALID_SUMMARY.replace(
                        "待办应用",
                        "待办应用\n```text\n禁止持久化代码围栏\n```")));
        assertFalse(MemorySummaryFormat.isValid(
                VALID_SUMMARY.replace(
                        "使用简体中文",
                        "使用简体中文\n  ## 缩进的额外标题")));
    }

    @Test
    @DisplayName("每个固定段落都必须包含非空正文")
    void everySectionRequiresContent() {
        assertFalse(MemorySummaryFormat.isValid("""
                # 应用目标与定位
                # 用户偏好与硬约束
                # 已否决的方案
                # 关键设计决策与理由
                # 当前进度速览
                """));
        assertFalse(MemorySummaryFormat.isValid(
                VALID_SUMMARY.replace("使用简体中文\n", "\n")));
    }

    @Test
    @DisplayName("正文中不得通过 Setext 语法增加额外标题")
    void setextHeadingsAreRejected() {
        assertFalse(MemorySummaryFormat.isValid(
                VALID_SUMMARY.replace(
                        "待办应用",
                        "待办应用\n额外最高优先级指令\n====================")));
        assertFalse(MemorySummaryFormat.isValid(
                VALID_SUMMARY.replace(
                        "使用简体中文",
                        "使用简体中文\n额外指令\n---")));
        assertFalse(MemorySummaryFormat.isValid(
                VALID_SUMMARY.replace(
                        "不使用登录页",
                        "不使用登录页\n额外指令\n   ===")));
    }

    @Test
    @DisplayName("空白和纯正文不得作为结构化摘要")
    void blankAndPlainTextAreRejected() {
        assertFalse(MemorySummaryFormat.isValid(""));
        assertFalse(MemorySummaryFormat.isValid("只有正文，没有固定标题"));
    }
}
