package com.lyw.appgeneration.ai.memory;

/** 不可信记忆文本进入持久化或模型上下文前的确定性字符边界。 */
public final class MemoryTextSafety {

    private MemoryTextSafety() {
    }

    public static boolean isSafe(String text) {
        if (text == null) {
            return false;
        }
        for (int index = 0; index < text.length();) {
            char current = text.charAt(index);
            if (Character.isSurrogate(current)) {
                if (!Character.isHighSurrogate(current)
                        || index + 1 >= text.length()
                        || !Character.isLowSurrogate(
                        text.charAt(index + 1))) {
                    return false;
                }
            }
            int codePoint = text.codePointAt(index);
            if (isUnsafe(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isUnsafe(int codePoint) {
        return isNonWhitespaceControl(codePoint)
                || isBidirectionalControl(codePoint)
                || isUnicodeNoncharacter(codePoint);
    }

    private static boolean isNonWhitespaceControl(int codePoint) {
        return Character.isISOControl(codePoint)
                && codePoint != '\t'
                && codePoint != '\n'
                && codePoint != '\r';
    }

    private static boolean isBidirectionalControl(int codePoint) {
        return codePoint == 0x061C
                || codePoint == 0x200E
                || codePoint == 0x200F
                || codePoint >= 0x202A && codePoint <= 0x202E
                || codePoint >= 0x2066 && codePoint <= 0x2069;
    }

    private static boolean isUnicodeNoncharacter(int codePoint) {
        return codePoint >= 0xFDD0 && codePoint <= 0xFDEF
                || (codePoint & 0xFFFF) == 0xFFFE
                || (codePoint & 0xFFFF) == 0xFFFF;
    }
}
