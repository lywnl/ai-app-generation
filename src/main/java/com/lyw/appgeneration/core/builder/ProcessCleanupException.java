package com.lyw.appgeneration.core.builder;

import java.io.IOException;

/** 取消后的进程树未能在期限内完整清理。 */
final class ProcessCleanupException extends IOException {

    ProcessCleanupException(String message, Throwable cause) {
        super(message, cause);
    }
}
