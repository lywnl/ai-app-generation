package com.lyw.appgeneration.ai.parser;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.tools.ToolStreamingSpec;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 工具调用参数的流式 JSON 字符级状态机解析器。
 * <p>
 * 每个 toolCallId 对应一个独立实例。AI 回调 onPartialToolExecutionRequest 时,
 * 把每个 arguments 片段喂给 {@link #feed(String)},产出事件:
 * <ul>
 *   <li>{@link ArgEventType#KEY_READY}   —— 某参数 key 解析完成(可用于"已知要调用哪个字段")</li>
 *   <li>{@link ArgEventType#DELTA}       —— 标注为 streaming 的字符串 value 的增量片段(转义已还原)</li>
 *   <li>{@link ArgEventType#VALUE_READY} —— 非流式参数 value 完整解析完成</li>
 * </ul>
 * <p>
 * 输入保证:只投喂"某工具单次 tool_call 的 arguments 拼接片段序列",完整拼接后是一个合法 JSON 对象。
 * 不支持嵌套对象/数组 value(当前所有文件工具参数都是字符串),若后续需要可按需扩展。
 */
public class ToolRequestStreamParser {

    public enum ArgEventType { KEY_READY, DELTA, VALUE_READY }

    @Getter
    public static class ArgEvent {
        public final ArgEventType type;
        public final String key;
        /** DELTA 时表示增量片段;VALUE_READY 时表示非流式完整 value;KEY_READY 时为 null */
        public final String payload;
        public ArgEvent(ArgEventType type, String key, String payload) {
            this.type = type;
            this.key = key;
            this.payload = payload;
        }
    }

    private enum State {
        EXPECT_OBJECT_START,
        EXPECT_KEY_OR_END,
        IN_KEY,
        EXPECT_COLON,
        EXPECT_VALUE,
        IN_STRING_VALUE,
        IN_STRING_ESCAPE,
        IN_UNICODE_ESCAPE,
        IN_LITERAL_VALUE,
        EXPECT_COMMA_OR_END,
        DONE
    }

    private final String toolName;
    private final Consumer<ArgEvent> sink;

    private State state = State.EXPECT_OBJECT_START;
    private final StringBuilder keyBuf = new StringBuilder();
    private final StringBuilder valueBuf = new StringBuilder();
    private String currentKey;
    private boolean currentKeyStreaming;
    /** streaming 模式下,本次 feed 内累积的待 flush 增量(减少 sink 调用次数) */
    private final StringBuilder deltaBuf = new StringBuilder();
    private int unicodeValue;
    private int unicodeDigits;
    private char pendingHighSurrogate;
    private boolean finished;

    public ToolRequestStreamParser(String toolName, Consumer<ArgEvent> sink) {
        this.toolName = toolName;
        this.sink = sink;
    }

    /** 便于测试:不走 sink,全部收集到 list */
    public static List<ArgEvent> collect(String toolName, String fullArguments) {
        List<ArgEvent> out = new ArrayList<>();
        ToolRequestStreamParser p = new ToolRequestStreamParser(toolName, out::add);
        p.feed(fullArguments);
        p.finish();
        return out;
    }

    public synchronized void feed(String chunk) {
        if (finished || StrUtil.isEmpty(chunk)) {
            return;
        }
        for (int i = 0; i < chunk.length(); i++) {
            step(chunk.charAt(i));
        }
        flushDelta();
    }

    /** 流结束时调用。当前仅校验状态,不做补救。 */
    public synchronized void finish() {
        if (finished) {
            return;
        }
        finished = true;
        flushDelta();
        // 若 AI 输出合规,应处于 DONE;否则静默结束(由调用方决定是否告警)
    }

    private void step(char c) {
        switch (state) {
            case EXPECT_OBJECT_START -> {
                if (Character.isWhitespace(c)) return;
                if (c == '{') { state = State.EXPECT_KEY_OR_END; }
                // 其它字符非法,忽略以提升鲁棒性
            }
            case EXPECT_KEY_OR_END -> {
                if (Character.isWhitespace(c) || c == ',') return;
                if (c == '"') { keyBuf.setLength(0); state = State.IN_KEY; return; }
                if (c == '}') { state = State.DONE; }
            }
            case IN_KEY -> {
                if (c == '"') {
                    currentKey = keyBuf.toString();
                    currentKeyStreaming = ToolStreamingSpec.isStreaming(toolName, currentKey);
                    sink.accept(new ArgEvent(ArgEventType.KEY_READY, currentKey, null));
                    state = State.EXPECT_COLON;
                } else if (c == '\\') {
                    // key 里理论上很少出现转义,简单吞下下一字符
                    state = State.IN_STRING_ESCAPE;
                    // 复用 IN_STRING_ESCAPE 的处理会污染 value 逻辑,这里直接就地处理:
                    // 但因为我们已经切到 IN_STRING_ESCAPE,需要标记来源——改为简单做法:
                    // 回退设计:key 不支持转义,遇到 \ 直接视为字面
                    state = State.IN_KEY;
                    keyBuf.append(c);
                } else {
                    keyBuf.append(c);
                }
            }
            case EXPECT_COLON -> {
                if (Character.isWhitespace(c)) return;
                if (c == ':') { state = State.EXPECT_VALUE; }
            }
            case EXPECT_VALUE -> {
                if (Character.isWhitespace(c)) return;
                if (c == '"') {
                    valueBuf.setLength(0);
                    state = State.IN_STRING_VALUE;
                } else {
                    // 字面量:true/false/null/数字
                    valueBuf.setLength(0);
                    valueBuf.append(c);
                    state = State.IN_LITERAL_VALUE;
                }
            }
            case IN_STRING_VALUE -> {
                if (c == '\\') {
                    state = State.IN_STRING_ESCAPE;
                } else if (c == '"') {
                    // value 结束
                    flushPendingHighSurrogate();
                    flushDelta();
                    if (!currentKeyStreaming) {
                        sink.accept(new ArgEvent(
                                ArgEventType.VALUE_READY, currentKey,
                                valueBuf.toString()));
                    }
                    resetAfterValue();
                } else {
                    appendValue(c);
                }
            }
            case IN_STRING_ESCAPE -> {
                if (c == 'u') {
                    unicodeValue = 0;
                    unicodeDigits = 0;
                    state = State.IN_UNICODE_ESCAPE;
                    return;
                }
                char unescaped = switch (c) {
                    case '"'  -> '"';
                    case '\\' -> '\\';
                    case '/'  -> '/';
                    case 'b'  -> '\b';
                    case 'f'  -> '\f';
                    case 'n'  -> '\n';
                    case 'r'  -> '\r';
                    case 't'  -> '\t';
                    default   -> c;
                };
                appendDecoded(unescaped);
                state = State.IN_STRING_VALUE;
            }
            case IN_UNICODE_ESCAPE -> {
                int digit = Character.digit(c, 16);
                if (digit < 0) {
                    flushPendingHighSurrogate();
                    appendDecoded('\uFFFD');
                    state = State.IN_STRING_VALUE;
                    step(c);
                    return;
                }
                unicodeValue = (unicodeValue << 4) | digit;
                unicodeDigits++;
                if (unicodeDigits == 4) {
                    appendDecoded((char) unicodeValue);
                    state = State.IN_STRING_VALUE;
                }
            }
            case IN_LITERAL_VALUE -> {
                if (c == ',' || c == '}' || Character.isWhitespace(c)) {
                    sink.accept(new ArgEvent(ArgEventType.VALUE_READY, currentKey, valueBuf.toString()));
                    resetAfterValue();
                    if (c == '}') state = State.DONE;
                    else if (c == ',') state = State.EXPECT_KEY_OR_END;
                    else state = State.EXPECT_COMMA_OR_END;
                } else {
                    valueBuf.append(c);
                }
            }
            case EXPECT_COMMA_OR_END -> {
                if (Character.isWhitespace(c)) return;
                if (c == ',') state = State.EXPECT_KEY_OR_END;
                else if (c == '}') state = State.DONE;
            }
            case DONE -> { /* 忽略尾随字符 */ }
        }
    }

    private void flushDelta() {
        if (deltaBuf.length() == 0) return;
        sink.accept(new ArgEvent(ArgEventType.DELTA, currentKey, deltaBuf.toString()));
        deltaBuf.setLength(0);
    }

    private void appendDecoded(char decoded) {
        if (Character.isHighSurrogate(decoded)) {
            flushPendingHighSurrogate();
            pendingHighSurrogate = decoded;
            return;
        }
        if (Character.isLowSurrogate(decoded) && pendingHighSurrogate != 0) {
            appendValue(pendingHighSurrogate);
            appendValue(decoded);
            pendingHighSurrogate = 0;
            return;
        }
        flushPendingHighSurrogate();
        appendValue(Character.isLowSurrogate(decoded) ? '\uFFFD' : decoded);
    }

    private void flushPendingHighSurrogate() {
        if (pendingHighSurrogate != 0) {
            appendValue('\uFFFD');
            pendingHighSurrogate = 0;
        }
    }

    private void appendValue(char value) {
        if (currentKeyStreaming) {
            deltaBuf.append(value);
        } else {
            valueBuf.append(value);
        }
    }

    private void resetAfterValue() {
        currentKey = null;
        currentKeyStreaming = false;
        valueBuf.setLength(0);
        deltaBuf.setLength(0);
        state = State.EXPECT_COMMA_OR_END;
    }
}
