package com.lyw.appgeneration.service.rag.exception;

/**
 * Rerank 调用失败专用异常
 * 设计意图:与 RagRetrievalService 的整体 catch 区分开,只降级 rerank 层
 * 不影响向量召回结果的使用
 *
 * @author lyw
 */
public class RerankException extends RuntimeException {

    public RerankException(String message) {
        super(message);
    }

    public RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}
