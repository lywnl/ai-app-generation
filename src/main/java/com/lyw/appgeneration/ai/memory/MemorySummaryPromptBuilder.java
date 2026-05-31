package com.lyw.appgeneration.ai.memory;

import cn.hutool.core.util.StrUtil;

/**
 * 构建 L1 滚动摘要的 prompt(5 段固定模板)。
 *
 * <p>借鉴 Claude Code SessionMemory:固定段落防止模型把摘要写成流水账;
 * token 超限时优先保"应用目标"与"用户偏好与硬约束"(最不可从代码推导、最该长期保真)。
 * 铁律——只摘"从当前代码状态推导不出来"的信息(已生成代码持久在 vue_project_&lt;appId&gt;,可随时读取,不进摘要)。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public final class MemorySummaryPromptBuilder {

    private MemorySummaryPromptBuilder() {
    }

    /** 整段摘要 token 预算(约,对齐 RAG max-context-chars:4000)。 */
    public static final int MAX_SUMMARY_TOKENS = 1800;

    private static final String TEMPLATE = """
            你是对话摘要助手。请基于【旧摘要】和【新增对话】,产出更新后的摘要。
            只记录"从当前代码状态推导不出来"的信息(已生成的代码不要复述)。
            严格使用下面 5 个固定段落,只更新内容,不要新增段落:

            # 应用目标与定位
            (用户要做什么应用、核心定位)
            # 用户偏好与硬约束
            (明确偏好,以及"不要X/要Y"的纠正 —— 含否定项,最重要)
            # 已否决的方案
            (试过但被用户否决的,防止重复犯错)
            # 关键设计决策与理由
            (记理由,不记代码结果)
            # 当前进度速览
            (一两句,指向 vue_project_<appId> 最新代码;不复制代码)

            规则:
            - 增量合并:在旧摘要基础上吸收新增对话,不要丢失旧的关键约束。
            - 控制在 %d token 以内;超了优先精简"当前进度速览",保住"应用目标"和"用户偏好与硬约束"。
            - 直接输出 5 段摘要正文,不要解释、不要寒暄。

            【旧摘要】
            %s

            【新增对话】
            %s
            """;

    /**
     * 构建摘要 prompt。
     *
     * @param oldSummary  上一次的摘要(首次为空)
     * @param newMessages 本次待并入的新增对话文本
     * @return 完整 prompt
     */
    public static String build(String oldSummary, String newMessages) {
        String old = StrUtil.isBlank(oldSummary) ? "(无,首次生成)" : oldSummary;
        return String.format(TEMPLATE, MAX_SUMMARY_TOKENS, old, newMessages);
    }
}
