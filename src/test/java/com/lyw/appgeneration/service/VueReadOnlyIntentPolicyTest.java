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
                "查看当前导航",
                "查看当前并发配置",
                "查看当前合并策略",
                "查看当前合并配置的显示逻辑",
                "读取当前配置",
                "解释购物车是怎么实现的",
                "解释购物车实现",
                "当前显示逻辑是什么",
                "当前隐藏状态是什么",
                "当前后端隐藏状态是什么",
                "刚才修改了哪些文件？",
                "请问，刚才修改了哪些文件？",
                "麻烦告诉我上次修改了什么。");
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
                Arguments.of("刚才改了哪些文件，做一个登录页"),
                Arguments.of("解释一下"),
                Arguments.of("解释这个"),
                Arguments.of("查看当前"),
                Arguments.of("查看当前这个"),
                Arguments.of("读取当前"),
                Arguments.of("读取当前它"),
                Arguments.of("分析当前"),
                Arguments.of("分析当前这个"),
                Arguments.of("查看当前导航，做一个登录页"),
                Arguments.of("查看当前导航，显示登录按钮"),
                Arguments.of("页面有哪些组件，隐藏侧边栏"),
                Arguments.of("查看当前导航做一个登录页"),
                Arguments.of("查看当前导航做一个页面"),
                Arguments.of("页面有哪些组件隐藏侧边栏"),
                Arguments.of("查看当前导航做个登录页面"),
                Arguments.of("查看当前导航隐藏登录页面"),
                Arguments.of("为什么帮我做个登录页面"),
                Arguments.of("查看当前它的配置"),
                Arguments.of("分析当前那个组件"),
                Arguments.of("解释这个页面实现"),
                Arguments.of("查看当前当前当前页面"),
                Arguments.of("当前当前当前页面是什么"),
                Arguments.of("查看当前显示隐藏显示页面"),
                Arguments.of("查看当前当前导航"),
                Arguments.of("读取当前当前配置"),
                Arguments.of("当前当前页面是什么"),
                Arguments.of("查看当前现在页面"),
                Arguments.of("当前现在页面是什么"),
                Arguments.of("查看当前导航并优化布局"),
                Arguments.of("分析完这个问题再调整页面"),
                Arguments.of("解释实现后帮我添加搜索框"),
                Arguments.of("读取配置后更新超时时间"),
                Arguments.of("查看当前组件并美化页面"),
                Arguments.of("把当前导航列出来"),
                Arguments.of("刚才修改了哪些文件，然后删除登录页"),
                Arguments.of("刚才修改了哪些文件，然后优化首页"),
                Arguments.of("上次修改了什么，顺便重构导航栏"),
                Arguments.of("继续"),
                Arguments.of(""),
                Arguments.of((String) null));
    }
}
