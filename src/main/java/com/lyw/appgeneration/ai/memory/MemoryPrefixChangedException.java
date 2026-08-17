package com.lyw.appgeneration.ai.memory;

/** 表示准备分层请求时，L0 已不再包含任务启动时确认的稳定前缀。 */
final class MemoryPrefixChangedException extends IllegalStateException {

    MemoryPrefixChangedException(String message) {
        super(message);
    }
}
