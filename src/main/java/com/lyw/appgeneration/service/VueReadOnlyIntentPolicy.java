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
            "创建", "新增", "删除", "修复", "重构", "替换", "改成", "换成",
            "丰富一点", "松散一点", "继续完善", "再现代一点");
    private static final List<String> READ_ONLY_PHRASES = List.of(
            "有哪些", "是什么", "为什么", "怎么实现的", "列出当前组件", "解释",
            "分析", "读取", "查看");

    public boolean isExplicitReadOnly(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String message = userMessage.strip();
        if (containsAny(message, MUTATION_PHRASES)) {
            return false;
        }
        if (isCompleteHistoryFactQuery(message)) {
            return true;
        }
        return containsAny(message, READ_ONLY_PHRASES);
    }

    /** 历史事实例外只接受完整问句，避免覆盖同句后续的新修改动作。 */
    private boolean isCompleteHistoryFactQuery(String message) {
        String normalized = stripPolitePrefix(stripTrailingPoliteText(message));
        return HISTORY_FACT_QUERIES.contains(normalized);
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
