package com.lyw.appgeneration.service;

/** Vue 回合分类模型无法给出受信模式时的系统异常。 */
public final class VueTurnModeRoutingException extends RuntimeException {

    public VueTurnModeRoutingException(String message) {
        super(message);
    }

    public VueTurnModeRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
