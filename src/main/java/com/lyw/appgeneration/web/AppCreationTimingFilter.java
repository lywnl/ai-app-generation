package com.lyw.appgeneration.web;

import com.lyw.appgeneration.monitor.AppCreationTiming;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** 仅观测创建接口，覆盖会话处理、参数绑定、控制器和响应序列化。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class AppCreationTimingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !(request.getContextPath() + "/app/add").equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String previousRequestId = MDC.get(AppCreationTiming.REQUEST_ID_KEY);
        String requestId = UUID.randomUUID().toString();
        String errorType = "NONE";
        MDC.put(AppCreationTiming.REQUEST_ID_KEY, requestId);
        try {
            log.info("[应用创建耗时] 请求开始,requestId={}", requestId);
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error error) {
            errorType = error.getClass().getSimpleName();
            throw error;
        } finally {
            try {
                log.info("[应用创建耗时] 请求结束,requestId={},appId={},totalMs={},httpStatus={},errorType={}",
                        requestId, request.getAttribute(AppCreationTiming.APP_ID_ATTRIBUTE),
                        AppCreationTiming.elapsedMillis(startedAt), response.getStatus(), errorType);
            } finally {
                // Servlet 线程会复用，退出时恢复原有标识，防止下一次请求串号。
                if (previousRequestId == null) {
                    MDC.remove(AppCreationTiming.REQUEST_ID_KEY);
                } else {
                    MDC.put(AppCreationTiming.REQUEST_ID_KEY, previousRequestId);
                }
            }
        }
    }
}
