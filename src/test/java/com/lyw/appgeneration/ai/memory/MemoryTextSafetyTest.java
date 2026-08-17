package com.lyw.appgeneration.ai.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryTextSafetyTest {

    @Test
    @DisplayName("仅允许制表换行回车三种控制空白")
    void onlyTabLineFeedAndCarriageReturnControlsAreAllowed() {
        assertTrue(MemoryTextSafety.isSafe("中文\t换行\n回车\rEmoji 🌙"));
        assertFalse(MemoryTextSafety.isSafe("垂直制表\u000B"));
        assertFalse(MemoryTextSafety.isSafe("换页\u000C"));
        assertFalse(MemoryTextSafety.isSafe("文件分隔符\u001C"));
    }

    @Test
    @DisplayName("双向文本控制符全部拒绝")
    void bidirectionalControlsAreRejected() {
        int[] bidiControls = {
                0x061C, 0x200E, 0x200F,
                0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
                0x2066, 0x2067, 0x2068, 0x2069
        };
        for (int codePoint : bidiControls) {
            assertFalse(MemoryTextSafety.isSafe(
                    "正文" + Character.toString(codePoint)),
                    Integer.toHexString(codePoint));
        }
    }

    @Test
    @DisplayName("孤立代理项和 Unicode 非字符不得进入持久化记忆")
    void malformedUnicodeAndNoncharactersAreRejected() {
        assertFalse(MemoryTextSafety.isSafe("孤立高代理\uD800"));
        assertFalse(MemoryTextSafety.isSafe("孤立低代理\uDC00"));
        assertFalse(MemoryTextSafety.isSafe("非字符\uFDD0"));
        assertFalse(MemoryTextSafety.isSafe(
                "非字符" + Character.toString(0x10FFFF)));
        assertTrue(MemoryTextSafety.isSafe("家庭 👨‍👩‍👧‍👦 与中文"));
    }
}
