package dev.langchain4j.service;

/** 普通生成链检测到服务端内部输出协议泄漏。 */
public final class InternalOutputProtocolException
        extends IllegalStateException {

    public InternalOutputProtocolException() {
        super("模型输出包含服务端内部协议标记");
    }
}
