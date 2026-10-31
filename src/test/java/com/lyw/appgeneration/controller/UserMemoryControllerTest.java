package com.lyw.appgeneration.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.GlobalExceptionHandler;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.vo.UserMemoryPageVO;
import com.lyw.appgeneration.service.UserMemoryManagementService;
import com.lyw.appgeneration.service.UserService;
import com.lyw.appgeneration.web.GenerationSsePreflightWriter;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserMemoryControllerTest {
    private final UserService users = mock(UserService.class);
    private final UserMemoryManagementService memories = mock(UserMemoryManagementService.class);
    private MockMvc mvc;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @AfterEach void 关闭测试指标() { metrics.close(); }

    @BeforeEach void 准备控制器() {
        User user = new User(); user.setId(7L);
        when(users.getLoginUser(any())).thenReturn(user);
        mvc = MockMvcBuilders.standaloneSetup(new UserMemoryController(users, memories))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
                .setControllerAdvice(new GlobalExceptionHandler(new GenerationSsePreflightWriter(new AppLifecycleMetricsCollector(metrics)))).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "preview", "change", "history"})
    void 所有入口都要求登录(String action) throws Exception {
        when(users.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        mvc.perform(post("/user/memory/" + action).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(memories);
    }

    @Test void 客户端伪造用户标识不改变会话归属() throws Exception {
        when(memories.list(eq(7L), any())).thenReturn(new UserMemoryPageVO(List.of(), 0, 1, 10, 0, 0));
        mvc.perform(post("/user/memory/list").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":8,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(memories).list(eq(7L), any());
        verify(memories, never()).list(eq(8L), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "9007199254740993", "1.5", "true"})
    void 不接受非字符串目标标识(String value) throws Exception {
        mvc.perform(post("/user/memory/change").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EDIT\",\"target\":{\"id\":" + value + ",\"revision\":\"1\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(40000));
        verifyNoInteractions(memories);
    }
}
