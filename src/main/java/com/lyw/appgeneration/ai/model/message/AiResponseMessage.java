package com.lyw.appgeneration.ai.model.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * AI 响应消息
 */
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
public class AiResponseMessage extends StreamMessage {

    private long generation;

    private String data;

    public AiResponseMessage(String data) {
        super(StreamMessageTypeEnum.AI_RESPONSE.getValue());
        this.data = data;
    }

    public AiResponseMessage(long generation, String data) {
        super(StreamMessageTypeEnum.AI_RESPONSE.getValue());
        this.generation = requirePositiveGeneration(generation);
        this.data = java.util.Objects.requireNonNull(
                data, "AI 正文不能为空");
    }

    public void setGeneration(long generation) {
        this.generation = requirePositiveGeneration(generation);
    }

    static long requirePositiveGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation 必须大于 0");
        }
        return generation;
    }
}
