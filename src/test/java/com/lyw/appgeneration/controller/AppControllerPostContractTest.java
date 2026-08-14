package com.lyw.appgeneration.controller;

import com.lyw.appgeneration.model.dto.app.AppChatGenerateRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AppControllerPostContractTest {

    @Test
    void 生成入口只暴露PostJson并返回Sse() throws Exception {
        Method method = AppController.class.getDeclaredMethod(
                "chatToGenCode", AppChatGenerateRequest.class,
                HttpServletRequest.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);

        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/chat/gen/code"}, mapping.value());
        assertArrayEquals(new String[]{MediaType.APPLICATION_JSON_VALUE},
                mapping.consumes());
        assertArrayEquals(new String[]{MediaType.TEXT_EVENT_STREAM_VALUE},
                mapping.produces());
        assertFalse(Arrays.stream(AppController.class.getDeclaredMethods())
                .filter(candidate -> candidate.isAnnotationPresent(
                        GetMapping.class))
                .map(candidate -> candidate.getAnnotation(GetMapping.class))
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .anyMatch("/chat/gen/code"::equals));
        assertEquals(2, method.getParameterCount());
    }
}
