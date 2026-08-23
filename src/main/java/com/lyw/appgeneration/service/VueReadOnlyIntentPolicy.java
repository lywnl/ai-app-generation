package com.lyw.appgeneration.service;

import org.springframework.stereotype.Component;

import java.util.List;

/** 只授予明确工程事实查询只读资格，其余输入一律失败关闭。 */
@Component
public class VueReadOnlyIntentPolicy {

    private static final List<String> HISTORY_FACT_QUERIES = List.of(
            "刚才改了哪些文件", "刚才修改了哪些文件", "上次修改了什么");
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
        if (containsAny(message, HISTORY_FACT_QUERIES)) {
            return true;
        }
        if (containsAny(message, MUTATION_PHRASES)) {
            return false;
        }
        return containsAny(message, READ_ONLY_PHRASES);
    }

    private boolean containsAny(String message, List<String> phrases) {
        return phrases.stream().anyMatch(message::contains);
    }
}
