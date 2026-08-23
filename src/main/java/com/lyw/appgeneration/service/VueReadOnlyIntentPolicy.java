package com.lyw.appgeneration.service;

import org.springframework.stereotype.Component;

import java.util.List;

/** 只授予明确工程事实查询只读资格，其余输入一律失败关闭。 */
@Component
public class VueReadOnlyIntentPolicy {

    private static final List<String> HISTORY_FACT_QUERIES = List.of(
            "刚才改了哪些文件", "刚才修改了哪些文件", "上次修改了什么");
    private static final List<String> POLITE_PREFIXES = List.of(
            "麻烦告诉我", "请问", "麻烦", "请");
    private static final List<String> POLITE_SUFFIXES = List.of(
            "谢谢", "麻烦了", "呢", "呀", "吗");
    private static final List<String> MUTATION_PHRASES = List.of(
            "修改", "创建", "新增", "增加", "添加", "删除", "移除", "修复", "重构",
            "替换", "改成", "换成", "优化", "调整", "更新", "美化", "补充", "移动",
            "重命名", "隐藏", "显示", "开启", "关闭", "启用", "禁用", "升级", "迁移",
            "丰富一点", "松散一点", "继续完善", "再现代一点");
    private static final List<String> MULTI_ACTION_CONNECTORS = List.of(
            "然后", "顺便", "接着", "并且", "并", "同时");

    public boolean isExplicitReadOnly(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String message = normalizePoliteQuery(userMessage);
        if (containsMultiActionConnector(message)
                || startsWithCommand(message)
                || containsPostActionCombination(message)) {
            return false;
        }
        if (isCompleteHistoryFactQuery(message)) {
            return true;
        }
        if (containsAny(message, MUTATION_PHRASES)) {
            return false;
        }
        return isExplicitSingleReadOnlyQuery(message);
    }

    /** 历史事实例外只接受完整问句，避免覆盖同句后续的新修改动作。 */
    private boolean isCompleteHistoryFactQuery(String message) {
        return HISTORY_FACT_QUERIES.contains(message);
    }

    private boolean isExplicitSingleReadOnlyQuery(String message) {
        return message.contains("有哪些") || message.endsWith("是什么")
                || message.startsWith("为什么")
                || message.contains("怎么实现的")
                || message.startsWith("列出当前组件")
                || message.startsWith("查看当前")
                || message.startsWith("读取当前")
                || message.startsWith("解释")
                || message.startsWith("分析当前");
    }

    private boolean startsWithCommand(String message) {
        return message.startsWith("把") || message.startsWith("帮我");
    }

    private boolean containsMultiActionConnector(String message) {
        if (containsAny(message, MULTI_ACTION_CONNECTORS)) {
            return true;
        }
        int againIndex = message.indexOf("再");
        return againIndex > 0 && againIndex < message.length() - 1
                && containsAny(message.substring(againIndex + 1), MUTATION_PHRASES);
    }

    private boolean containsPostActionCombination(String message) {
        int afterIndex = message.indexOf('后');
        if (afterIndex < 0 || afterIndex == message.length() - 1) {
            return false;
        }
        String following = message.substring(afterIndex + 1);
        return following.startsWith("帮我")
                || containsAny(following, MUTATION_PHRASES);
    }

    private String normalizePoliteQuery(String message) {
        return stripPolitePrefix(stripTrailingPoliteText(message.strip()));
    }

    private String stripPolitePrefix(String message) {
        for (String prefix : POLITE_PREFIXES) {
            if (message.startsWith(prefix)) {
                return stripLeadingPunctuation(
                        message.substring(prefix.length()).stripLeading());
            }
        }
        return message;
    }

    private String stripTrailingPoliteText(String message) {
        String normalized = message.stripTrailing();
        boolean changed;
        do {
            String withoutPunctuation = stripTrailingPunctuation(normalized);
            String withoutSuffix = stripTrailingSuffix(withoutPunctuation);
            changed = !withoutSuffix.equals(normalized);
            normalized = withoutSuffix;
        } while (changed);
        return normalized;
    }

    private String stripTrailingPunctuation(String message) {
        int end = message.length();
        while (end > 0 && isPunctuation(message.charAt(end - 1))) {
            end--;
        }
        return message.substring(0, end).stripTrailing();
    }

    private String stripLeadingPunctuation(String message) {
        int start = 0;
        while (start < message.length() && isPunctuation(message.charAt(start))) {
            start++;
        }
        return message.substring(start).stripLeading();
    }

    private boolean isPunctuation(char character) {
        return character == '，' || character == '。' || character == '！'
                || character == '？' || character == ',' || character == '.'
                || character == '!' || character == '?';
    }

    private String stripTrailingSuffix(String message) {
        for (String suffix : POLITE_SUFFIXES) {
            if (message.endsWith(suffix)) {
                return message.substring(0, message.length() - suffix.length())
                        .stripTrailing();
            }
        }
        return message;
    }

    private boolean containsAny(String message, List<String> phrases) {
        return phrases.stream().anyMatch(message::contains);
    }
}
