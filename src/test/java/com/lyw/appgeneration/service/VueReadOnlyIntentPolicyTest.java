package com.lyw.appgeneration.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VueReadOnlyIntentPolicyTest {

    private final VueReadOnlyIntentPolicy policy = new VueReadOnlyIntentPolicy();

    @ParameterizedTest
    @MethodSource("明确只读样例")
    void 明确工程事实查询具备只读资格(String message) {
        assertEquals(true, policy.isExplicitReadOnly(message));
    }

    private static Stream<String> 明确只读样例() {
        return Stream.of(
                "现在页面有哪些组件？",
                "解释购物车是怎么实现的",
                "刚才修改了哪些文件？",
                "上次修改了什么");
    }

    @ParameterizedTest
    @MethodSource("非只读样例")
    void 修改混合和歧义输入不具备只读资格(String message) {
        assertEquals(false, policy.isExplicitReadOnly(message));
    }

    private static Stream<Arguments> 非只读样例() {
        return Stream.of(
                Arguments.of("把按钮改成红色"),
                Arguments.of("分析这个错误并修复"),
                Arguments.of("内容丰富一点"),
                Arguments.of("排版松散一点"),
                Arguments.of("再现代一点"),
                Arguments.of("为什么不把按钮改成红色"),
                Arguments.of("继续"),
                Arguments.of(""),
                Arguments.of((String) null));
    }
}
