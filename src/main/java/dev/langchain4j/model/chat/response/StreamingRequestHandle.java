package dev.langchain4j.model.chat.response;

/** 可取消的单次流式模型请求句柄。 */
@FunctionalInterface
public interface StreamingRequestHandle {

    void cancel();
}
