package com.lyw.appgeneration.ai.memory;

import cn.hutool.core.util.StrUtil;

/**
 * 构建 L2 用户偏好抽取 prompt。
 *
 * <p>借鉴 Claude Code memdir 封闭类型系统:半封闭 name 类别清单稳定去重键;
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

            name 字段优先归类到以下固定类别(降低重复):
            语言偏好 / 视觉风格 / 技术栈倾向 / 交互习惯 / 其他

            参考【已有偏好】决定每条是新增、更新还是无变化:
            - 同类偏好用相同 name(便于去重更新);内容有变化则给出最新 content。

            输出格式(严格):
            - 仅输出 JSON 数组,每个元素 {"name": "类别", "content": "具体偏好"}。
            - 没有可抽取的跨应用偏好时,输出空数组 []。
            - 不要输出任何解释、Markdown 代码块标记或多余文字。

            【已有偏好】
            %s

            【新增对话】
            %s
            """;

    public static String build(String existingPreferences, String newMessages) {
        String existing = StrUtil.isBlank(existingPreferences) ? "(无,首次抽取)" : existingPreferences;
        return String.format(TEMPLATE, existing, newMessages);
    }
}
