package com.lyw.appgeneration.ai.memory;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 构建 L2 用户偏好抽取 prompt。
 *
 * <p>借鉴 Claude Code memdir 封闭类型系统:固定 name 类别清单稳定去重键;
 * 明确"只抽跨 app 通用偏好、排除 app 特有需求",杜绝与 L1 摘要重叠。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public final class UserPreferencePromptBuilder {

    private UserPreferencePromptBuilder() {
    }

    private static final String TEMPLATE = """
            你是用户偏好抽取助手。请从【新增对话】中识别该用户**跨应用通用**的偏好。

            判定标准(严格遵守):
            - 只抽取"与具体应用无关、对该用户的任何应用都成立"的通用偏好,例如:语言、视觉风格、技术栈倾向、交互习惯。
            - 应用特有的功能需求**不要抽**(例如"这个待办应用要三档分类"——那属于单应用记忆,不是跨应用偏好)。
            - 构建错误、工具选择、修复尝试、依赖安装、框架堆栈和单项目代码决策都是临时或单应用信息,一律不要抽取。
            - 偏好证据只能来自用户文本。禁止从 AI 模型行为、工具结果或未由用户文本表达的单次行为猜测用户偏好。

            name 字段只能使用以下固定类别(降低重复):
            语言偏好 / 视觉风格 / 技术栈倾向 / 交互习惯 / 其他

            参考【已有偏好】决定每条是新增、更新还是无变化:
            - 同类偏好用相同 name(便于去重更新);内容有变化则给出最新 content。
            - 已有同内容偏好若从本批新 turn 获得了新证据，仍必须再次输出该候选，本批 turnIds 只填写提供新证据的白名单 ID。
            - 只有本批没有新证据才省略已有同内容偏好。

            证据类型(严格):
            - evidenceType 只能是 EXPLICIT 或 IMPLICIT。
            - EXPLICIT 表示用户直接、明确表达了跨应用偏好。
            - IMPLICIT 表示用户文本中尚未明确声明为长期偏好、但可能跨应用复用的选择或要求；允许从单个完整回合输出为弱证据候选。
            - 单回合 IMPLICIT 不得表述为已确认的长期偏好；是否激活只由服务端判定，只有不同 turnId 累计达到 2 才 ACTIVE。
            - turnIds 必须非空，且只能使用【本批 turnId 白名单】中的不同 ID。

            输出格式(严格):
            - 仅输出 JSON 数组,每个元素 {"name": "类别", "content": "具体偏好", "evidenceType": "EXPLICIT", "turnIds": [10001]}。
            - 没有可抽取的跨应用偏好时,输出空数组 []。
            - 不要输出任何解释、Markdown 代码块标记或多余文字。

            【已有偏好】
            %s

            【本批 turnId 白名单】
            %s

            【新增用户证据】
            %s
            """;

    public static String build(String existingPreferences, String newMessages) {
        return build(existingPreferences, newMessages, List.of());
    }

    public static String build(String existingPreferences,
                               String userEvidence,
                               List<Long> turnIds) {
        String existing = StrUtil.isBlank(existingPreferences) ? "(无,首次抽取)" : existingPreferences;
        String whitelist = turnIds == null || turnIds.isEmpty()
                ? "(空)"
                : turnIds.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        return String.format(TEMPLATE, existing, whitelist, userEvidence);
    }
}
