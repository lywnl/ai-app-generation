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
            "重命名", "开启", "关闭", "启用", "禁用", "升级", "迁移",
            "丰富一点", "松散一点", "继续完善", "再现代一点");
    private static final List<String> COMMAND_ACTION_PHRASES = List.of(
            "显示", "隐藏", "修改", "创建", "新增", "增加", "添加", "删除", "移除", "修复",
            "重构", "替换", "改成", "换成", "优化", "调整", "更新", "美化", "补充", "移动",
            "重命名", "开启", "关闭", "启用", "禁用", "升级", "迁移");
    private static final List<String> MULTI_ACTION_CONNECTORS = List.of(
            "然后", "顺便", "接着", "并且", "同时");

    public boolean isExplicitReadOnly(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String message = normalizePoliteQuery(userMessage);
        if (isCompleteHistoryFactQuery(message)) {
            return true;
        }
        if (hasTrailingContentAfterHistoryFactQuery(message)) {
            return false;
        }
        if (containsMultiActionConnector(message)
                || startsWithCommand(message)
                || containsPostActionCombination(message)) {
            return false;
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

    /** 历史事实问句不允许拼接任何后续内容，避免后续动作借由问句获得只读资格。 */
    private boolean hasTrailingContentAfterHistoryFactQuery(String message) {
        return HISTORY_FACT_QUERIES.stream()
                .anyMatch(historyQuery -> message.startsWith(historyQuery));
    }

    private boolean isExplicitSingleReadOnlyQuery(String message) {
        return hasContentAround(message, "有哪些")
                || hasContentBefore(message, "是什么")
                || hasContentAfterPrefix(message, "为什么")
                || hasContentBefore(message, "怎么实现的")
                || message.startsWith("列出当前组件")
                || hasContentAfterPrefix(message, "查看当前")
                || hasContentAfterPrefix(message, "读取当前")
                || hasExplanationSubject(message)
                || hasContentAfterPrefix(message, "分析当前");
    }

    private boolean startsWithCommand(String message) {
        return message.startsWith("把") || message.startsWith("帮我")
                || message.startsWith("显示") || message.startsWith("隐藏");
    }

    private boolean containsMultiActionConnector(String message) {
        if (containsAny(message, MULTI_ACTION_CONNECTORS)) {
            return true;
        }
        int andIndex = message.indexOf("并");
        if (andIndex > 0 && andIndex < message.length() - 1
                && containsAny(message.substring(andIndex + 1), COMMAND_ACTION_PHRASES)) {
            return true;
        }
        int againIndex = message.indexOf("再");
        return againIndex > 0 && againIndex < message.length() - 1
                && containsAny(message.substring(againIndex + 1), COMMAND_ACTION_PHRASES);
    }

    private boolean containsPostActionCombination(String message) {
        int afterIndex = message.indexOf('后');
        if (afterIndex < 0 || afterIndex == message.length() - 1) {
            return false;
        }
        String following = message.substring(afterIndex + 1);
        return following.startsWith("帮我")
                || containsAny(following, COMMAND_ACTION_PHRASES);
    }

    private boolean hasContentAround(String message, String phrase) {
        int phraseIndex = message.indexOf(phrase);
        return phraseIndex > 0 && phraseIndex + phrase.length() < message.length();
    }

    private boolean hasContentBefore(String message, String suffix) {
        return message.endsWith(suffix) && message.length() > suffix.length();
    }

    private boolean hasContentAfterPrefix(String message, String prefix) {
        return message.startsWith(prefix) && message.length() > prefix.length();
    }

    private boolean hasExplanationSubject(String message) {
        if (!hasContentAfterPrefix(message, "解释")) {
            return false;
        }
        String subject = message.substring("解释".length());
        return !subject.equals("一下") && !subject.equals("下");
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
