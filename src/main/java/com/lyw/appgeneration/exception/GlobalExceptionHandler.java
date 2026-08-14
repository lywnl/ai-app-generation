package com.lyw.appgeneration.exception;

import com.lyw.appgeneration.common.BaseResponse;
import com.lyw.appgeneration.common.ResultUtils;
import com.lyw.appgeneration.web.GenerationSsePreflightWriter;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;


/**
 * 全局异常处理器
 */
@Hidden
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private final GenerationSsePreflightWriter preflightWriter;

    public GlobalExceptionHandler(
            GenerationSsePreflightWriter preflightWriter) {
        this.preflightWriter = preflightWriter;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public BaseResponse<?> httpMessageNotReadableExceptionHandler(
            HttpMessageNotReadableException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        GenerationPreflightException preflight =
                GenerationPreflightException.business(
                        ErrorCode.PARAMS_ERROR.getCode(),
                        "请求JSON格式错误", exception);
        if (preflightWriter.writeIfApplicable(
                request, response, preflight)) {
            return null;
        }
        if (preflightWriter.shouldSkipDuplicateWrite(request, response)) {
            log.warn("生成 SSE 已提交，跳过迟到的 JSON 异常响应,path={}",
                    request.getRequestURI());
            return null;
        }
        log.warn("请求JSON格式错误,path={}", request.getRequestURI());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR,
                "请求JSON格式错误");
    }

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}
