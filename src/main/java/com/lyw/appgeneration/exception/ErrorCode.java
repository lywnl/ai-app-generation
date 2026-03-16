package com.lyw.appgeneration.exception;

import lombok.Getter;

/**
 * 错误码枚举
 *
 * @author lyw
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAMS_ERROR(40000, "请求参数错误"),
    NOT_LOGIN_ERROR(40100, "未登录"),
    NO_AUTH_ERROR(40101, "无权限"),
    NOT_FOUND_ERROR(40400, "请求数据不存在"),
    FORBIDDEN_ERROR(40300, "禁止访问"),
    TOO_MANY_REQUEST(42900, "请求过于频繁"),
    SYSTEM_ERROR(50000, "系统内部异常"),
    OPERATION_ERROR(50001, "操作失败"),

    // 邮箱相关错误码
    EMAIL_SEND_ERROR(50100, "邮件发送失败"),
    EMAIL_CODE_INVALID(40001, "验证码无效或已过期"),
    EMAIL_EXISTS(40002, "邮箱已存在"),
    EMAIL_SEND_FREQUENT(42901, "发送过于频繁,请稍后再试"),
    EMAIL_FORMAT_ERROR(40003, "邮箱格式不正确");

    /**
     * 状态码
     */
    private final int code;

    /**
     * 信息
     */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

}