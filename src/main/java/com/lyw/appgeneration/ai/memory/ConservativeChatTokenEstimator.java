package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 面向中英文和工具 JSON 的保守 Token 估算器。
 *
 * <p>DeepSeek 未向当前 Java 调用链提供请求前的精确 tokenizer，因而这里采用稳定、
 * 可测试且偏保守的本地口径，并通过配置安全系数吸收模型 tokenizer 差异。</p>
 */
@Component
public class ConservativeChatTokenEstimator implements ChatTokenEstimator {

    private static final int MESSAGE_OVERHEAD = 8;
    private static final int MESSAGE_NAME_OVERHEAD = 4;
    private static final int TOOL_PAYLOAD_OVERHEAD = 12;
    private static final int TOOL_SPECIFICATION_OVERHEAD = 12;
    private static final int REQUEST_OVERHEAD = 16;
    private static final int NON_TEXT_CONTENT_OVERHEAD = 256;

    private final MemoryTokenProperties properties;

    public ConservativeChatTokenEstimator(MemoryTokenProperties properties) {
        this.properties = Objects.requireNonNull(
                properties, "Token 预算配置不能为空");
    }

    @Override
    public int estimateText(String text) {
        return applySafetyFactor(rawTextTokens(text));
    }

    @Override
    public int estimateMessages(List<ChatMessage> messages) {
        return applySafetyFactor(rawMessagesTokens(messages));
    }

    @Override
    public int estimateToolSpecifications(List<ToolSpecification> tools) {
        return applySafetyFactor(rawToolSpecificationsTokens(tools));
    }

    @Override
    public int estimateRequest(List<ChatMessage> messages,
                               List<ToolSpecification> tools) {
        long raw = (long) rawMessagesTokens(messages)
                + rawToolSpecificationsTokens(tools)
                + REQUEST_OVERHEAD;
        return applySafetyFactor(raw);
    }

    private int rawMessagesTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long tokens = 0L;
        for (ChatMessage message : messages) {
            if (message != null) {
                tokens += rawMessageTokens(message);
            }
        }
        return saturatingInt(tokens);
    }

    private int rawMessageTokens(ChatMessage message) {
        long tokens = MESSAGE_OVERHEAD;
        if (message instanceof SystemMessage systemMessage) {
            tokens += rawTextTokens(systemMessage.text());
        } else if (message instanceof UserMessage userMessage) {
            tokens += rawNamedMessageTokens(userMessage.name());
            tokens += rawUserContentsTokens(userMessage.contents());
        } else if (message instanceof AiMessage aiMessage) {
            tokens += rawTextTokens(aiMessage.text());
            tokens += rawToolRequestsTokens(aiMessage.toolExecutionRequests());
        } else if (message instanceof ToolExecutionResultMessage toolResult) {
            tokens += TOOL_PAYLOAD_OVERHEAD;
            tokens += rawTextTokens(toolResult.id());
            tokens += rawTextTokens(toolResult.toolName());
            tokens += rawTextTokens(toolResult.text());
        } else {
            tokens += rawTextTokens(message.toString());
        }
        return saturatingInt(tokens);
    }

    private int rawNamedMessageTokens(String name) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        return MESSAGE_NAME_OVERHEAD + rawTextTokens(name);
    }

    private int rawUserContentsTokens(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }
        long tokens = 0L;
        for (Content content : contents) {
            if (content instanceof TextContent textContent) {
                tokens += rawTextTokens(textContent.text());
            } else if (content != null) {
                tokens += NON_TEXT_CONTENT_OVERHEAD;
                tokens += rawTextTokens(content.toString());
            }
        }
        return saturatingInt(tokens);
    }

    private int rawToolRequestsTokens(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return 0;
        }
        long tokens = 0L;
        for (ToolExecutionRequest request : requests) {
            if (request == null) {
                continue;
            }
            tokens += TOOL_PAYLOAD_OVERHEAD;
            tokens += rawTextTokens(request.id());
            tokens += rawTextTokens(request.name());
            tokens += rawTextTokens(request.arguments());
        }
        return saturatingInt(tokens);
    }

    private int rawToolSpecificationsTokens(List<ToolSpecification> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        long tokens = 0L;
        for (ToolSpecification tool : tools) {
            if (tool == null) {
                continue;
            }
            tokens += TOOL_SPECIFICATION_OVERHEAD;
            tokens += rawTextTokens(tool.name());
            tokens += rawTextTokens(tool.description());
            if (tool.parameters() != null) {
                tokens += rawTextTokens(tool.parameters().toString());
            }
        }
        return saturatingInt(tokens);
    }

    private int rawTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjkTokens = 0L;
        long symbolTokens = 0L;
        long asciiCharacters = 0L;
        long otherUtf8Bytes = 0L;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint <= 0x7F) {
                asciiCharacters++;
            } else if (isCjk(codePoint)) {
                cjkTokens++;
            } else if (isSymbol(codePoint)) {
                symbolTokens += 2L;
            } else {
                otherUtf8Bytes += utf8Length(codePoint);
            }
        }
        long tokens = cjkTokens + symbolTokens;
        tokens += divideAndRoundUp(asciiCharacters, 4L);
        tokens += divideAndRoundUp(otherUtf8Bytes, 3L);
        return saturatingInt(tokens);
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private boolean isSymbol(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.OTHER_SYMBOL
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL;
    }

    private int utf8Length(int codePoint) {
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    private long divideAndRoundUp(long value, long divisor) {
        return value == 0L ? 0L : (value + divisor - 1L) / divisor;
    }

    private int applySafetyFactor(long rawTokens) {
        if (rawTokens <= 0L) {
            return 0;
        }
        double guarded = Math.ceil(
                rawTokens * properties.getEstimationSafetyFactor());
        return guarded >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) guarded;
    }

    private int saturatingInt(long value) {
        return value >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }
}
