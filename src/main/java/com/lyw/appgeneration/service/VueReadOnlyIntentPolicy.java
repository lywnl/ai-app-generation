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
    private static final List<String> ENGINEERING_ENTITIES = List.of(
            "组件名称", "页面", "组件", "导航", "配置", "策略", "逻辑", "状态", "实现", "购物车", "首页");
    private static final List<String> ENGINEERING_QUALIFIERS = List.of(
            "当前", "现在", "全部", "后端", "并发", "合并", "显示", "隐藏");

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
        if (hasClauseDelimiter(message) || startsWithCommand(message)) {
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
        return isInspectionQuery(message, "查看当前")
                || isInspectionQuery(message, "读取当前")
                || isInspectionQuery(message, "分析当前")
                || isComponentListQuery(message)
                || isCurrentDefinitionQuery(message)
                || isReasonQuery(message)
                || isImplementationQuery(message)
                || message.equals("列出当前组件");
    }

    private boolean startsWithCommand(String message) {
        return message.startsWith("把") || message.startsWith("帮我")
                || message.startsWith("显示") || message.startsWith("隐藏");
    }

    /** 只读资格只接受一个自然语言问句；出现分句分隔符即按混合输入失败关闭。 */
    private boolean hasClauseDelimiter(String message) {
        return message.indexOf('，') >= 0 || message.indexOf(',') >= 0
                || message.indexOf('；') >= 0 || message.indexOf(';') >= 0
                || message.indexOf('、') >= 0 || message.indexOf('\n') >= 0;
    }

    private boolean isInspectionQuery(String message, String prefix) {
        if (!message.startsWith(prefix)) {
            return false;
        }
        return isEngineeringSubject(message.substring(prefix.length()));
    }

    private boolean isComponentListQuery(String message) {
        int phraseIndex = message.indexOf("有哪些");
        if (phraseIndex <= 0 || phraseIndex + "有哪些".length() >= message.length()) {
            return false;
        }
        return isEngineeringSubject(message.substring(0, phraseIndex))
                && isEngineeringSubject(message.substring(phraseIndex + "有哪些".length()));
    }

    private boolean isCurrentDefinitionQuery(String message) {
        if (!message.startsWith("当前") || !message.endsWith("是什么")) {
            return false;
        }
        return isEngineeringSubject(message.substring("当前".length(),
                message.length() - "是什么".length()));
    }

    private boolean isReasonQuery(String message) {
        return message.startsWith("为什么")
                && isEngineeringSubject(message.substring("为什么".length()));
    }

    private boolean isImplementationQuery(String message) {
        if (message.startsWith("解释")) {
            String subject = message.substring("解释".length());
            return hasConcretePrefixBeforeSuffix(subject, "怎么实现的")
                    || hasConcretePrefixBeforeSuffix(subject, "实现");
        }
        return hasConcretePrefixBeforeSuffix(message, "怎么实现的");
    }

    private boolean hasConcretePrefixBeforeSuffix(String message, String suffix) {
        if (!message.endsWith(suffix)) {
            return false;
        }
        String subject = message.substring(0, message.length() - suffix.length());
        if (subject.endsWith("是")) {
            subject = subject.substring(0, subject.length() - 1);
        }
        return isEngineeringSubject(subject);
    }

    private boolean isEngineeringSubject(String subject) {
        int index = 0;
        while (index < subject.length()) {
            int relationIndex = nextRelationIndex(subject, index);
            int segmentEnd = relationIndex < 0 ? subject.length() : relationIndex;
            if (!isEngineeringSegment(subject.substring(index, segmentEnd))) {
                return false;
            }
            if (relationIndex < 0) {
                return true;
            }
            index = relationIndex + relationLength(subject, relationIndex);
        }
        return false;
    }

    /** 工程对象只允许“限定词* + 实体”，并可用“的/中的”连接多个此类片段。 */
    private boolean isEngineeringSegment(String segment) {
        int index = 0;
        String qualifier;
        while ((qualifier = matchingPrefix(segment, index, ENGINEERING_QUALIFIERS)) != null) {
            index += qualifier.length();
        }
        String entity = matchingPrefix(segment, index, ENGINEERING_ENTITIES);
        return entity != null && index + entity.length() == segment.length();
    }

    private int nextRelationIndex(String subject, int startIndex) {
        int possessiveIndex = subject.indexOf('的', startIndex);
        int locativeIndex = subject.indexOf("中的", startIndex);
        if (locativeIndex < 0) {
            return possessiveIndex;
        }
        if (possessiveIndex < 0 || locativeIndex < possessiveIndex) {
            return locativeIndex;
        }
        return possessiveIndex;
    }

    private int relationLength(String subject, int relationIndex) {
        return subject.startsWith("中的", relationIndex) ? 2 : 1;
    }

    private String matchingPrefix(String text, int startIndex, List<String> candidates) {
        return candidates.stream()
                .filter(candidate -> text.startsWith(candidate, startIndex))
                .findFirst()
                .orElse(null);
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
