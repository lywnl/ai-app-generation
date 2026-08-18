package dev.langchain4j.service;

/** 流式分片与供应商完整响应无法按前缀关系拼接时的普通一致性错误。 */
public final class StreamingResponseConsistencyException
        extends IllegalStateException {

    public StreamingResponseConsistencyException() {
        super("完整响应与已观察流式分片不一致");
    }
}
