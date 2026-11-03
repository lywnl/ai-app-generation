package com.lyw.appgeneration.controller;

import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.handler.GenerationCancellationRegistry;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.GlobalExceptionHandler;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import com.lyw.appgeneration.service.ProjectDownloadService;
import com.lyw.appgeneration.service.UserService;
import com.lyw.appgeneration.service.impl.AppServiceImpl;
import com.lyw.appgeneration.service.impl.ChatHistoryServiceImpl;
import com.lyw.appgeneration.web.GenerationSsePreflightWriter;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实控制器、权限服务与 Jackson 链路；仅替换登录身份、存储和外部副作用。 */
class AppReadonlyHttpContractTest {
    private final UserService users = mock(UserService.class);
    private final AiCodeGeneratorFacade generator = mock(AiCodeGeneratorFacade.class);
    private final GenerationCancellationRegistry cancellations = new GenerationCancellationRegistry();
    private final AtomicInteger cancellationCalls = new AtomicInteger();
    private final ProjectDownloadService downloads = mock(ProjectDownloadService.class);
    private final AppOperationLeaseManager leases = new AppOperationLeaseManager();
    private AppServiceImpl apps;
    private ChatHistoryServiceImpl histories;
    private MockMvc mvc;
    private ChatHistory original;

    @BeforeEach
    void setUp() {
        apps = spy(new AppServiceImpl());
        histories = spy(new ChatHistoryServiceImpl());
        var deployed = App.builder().id(7L).userId(9L).priority(99).codeGenType("vue_project")
                .deployKey("published").deployedTime(LocalDateTime.of(2026, 11, 3, 10, 0)).build();
        doReturn(deployed).when(apps).getById(7L);
        doReturn(App.builder().id(8L).userId(9L).priority(99).build()).when(apps).getById(8L);
        doReturn(App.builder().id(9L).userId(9L).priority(0).deployKey("not-featured")
                .deployedTime(LocalDateTime.of(2026, 11, 3, 10, 0)).build()).when(apps).getById(9L);
        ReflectionTestUtils.setField(apps, "aiCodeGeneratorFacade", generator);
        ReflectionTestUtils.setField(apps, "generationCancellationRegistry", cancellations);
        cancellations.register(7L, "00000000-0000-4000-8000-000000000001", 9L,
                cancellationCalls::incrementAndGet);
        ReflectionTestUtils.setField(apps, "projectDownloadService", downloads);
        ReflectionTestUtils.setField(apps, "appOperationLeaseManager", leases);
        ReflectionTestUtils.setField(histories, "appService", apps);
        original = ChatHistory.builder().id(71L).appId(7L).userId(9L).messageType("ai")
                .message("公开的页面生成对话").memoryMessage("INTERNAL_MEMORY_MATERIAL")
                .memoryOutcome(ChatMemoryOutcome.SUCCEEDED).decisionEvidence("INTERNAL_DECISION_EVIDENCE")
                .build();
        doReturn(new Page<>(List.of(original), 1, 10, 1))
                .when(histories).page(any(Page.class), any(QueryWrapper.class));
        when(users.getLoginUser(any())).thenReturn(User.builder().id(10L).userRole("user").build());

        var chatController = new ChatHistoryController();
        ReflectionTestUtils.setField(chatController, "chatHistoryService", histories);
        ReflectionTestUtils.setField(chatController, "userService", users);
        var appController = new AppController();
        ReflectionTestUtils.setField(appController, "appService", apps);
        ReflectionTestUtils.setField(appController, "userService", users);
        var metrics = new AppLifecycleMetricsCollector(new SimpleMeterRegistry());
        ReflectionTestUtils.setField(appController, "appLifecycleMetricsCollector", metrics);
        mvc = MockMvcBuilders.standaloneSetup(chatController, appController)
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .setControllerAdvice(new GlobalExceptionHandler(new GenerationSsePreflightWriter(metrics))).build();
    }

    @Test
    void 普通访客的真实HTTP响应不携带内部记忆内容() throws Exception {
        mvc.perform(get("/chatHistory/app/7").param("view", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].message").value("公开的页面生成对话"))
                .andExpect(jsonPath("$.data.records[0].memoryMessage").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].memoryOutcome").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].decisionEvidence").doesNotExist())
                .andExpect(content().string(not(containsString("INTERNAL_"))));
        assertEquals("INTERNAL_MEMORY_MATERIAL", original.getMemoryMessage());
        assertNoSideEffects();
        verify(histories, never()).save(any(ChatHistory.class));
    }

    @Test
    void 作者的原历史响应保持兼容且决策证据仍不外发() throws Exception {
        when(users.getLoginUser(any())).thenReturn(User.builder().id(9L).userRole("user").build());
        mvc.perform(get("/chatHistory/app/7"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].memoryMessage").value("INTERNAL_MEMORY_MATERIAL"))
                .andExpect(jsonPath("$.data.records[0].memoryOutcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.records[0].decisionEvidence").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(longs = {8L, 9L})
    void view参数不能绕过精选与部署的联合条件(long appId) throws Exception {
        mvc.perform(get("/chatHistory/app/" + appId).param("view", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()));
        verify(histories, never()).page(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void 未登录不能通过已部署历史接口读取() throws Exception {
        when(users.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        mvc.perform(get("/chatHistory/app/7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));
        verify(histories, never()).listAppChatHistoryByPage(any(), anyInt(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "deploy", "download", "update", "delete"})
    void 已部署应用的写入口仍在副作用之前拒绝普通访客(String operation) throws Exception {
        MockHttpServletRequestBuilder request = switch (operation) {
            case "cancel" -> post("/app/chat/gen/cancel").content("{\"appId\":\"7\",\"generationId\":\"00000000-0000-4000-8000-000000000001\"}");
            case "deploy" -> post("/app/deploy").content("{\"appId\":7}");
            case "download" -> get("/app/download/7");
            case "update" -> post("/app/update").content("{\"id\":7,\"appName\":\"非法改名\"}");
            case "delete" -> post("/app/delete").content("{\"id\":7}");
            default -> throw new IllegalArgumentException(operation);
        };
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()));
        assertNoSideEffects();
        verify(apps, never()).updateById(any(App.class));
        verify(histories, never()).save(any(ChatHistory.class));
    }

    @Test
    void 已部署应用的生成入口通过真实SSE错误通道拒绝访客() throws Exception {
        var result = mvc.perform(post("/app/chat/gen/code")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"appId\":\"7\",\"message\":\"继续修改\",\"generationId\":\"00000000-0000-4000-8000-000000000001\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().string(containsString("business-error")))
                .andExpect(content().string(containsString("\"code\":40101")))
                .andExpect(response -> assertTrue(response.getResponse()
                        .getContentAsString(StandardCharsets.UTF_8).contains("无权限访问该应用")))
                .andExpect(content().string(containsString("done")));
        assertNoSideEffects();
        verify(histories, never()).save(any(ChatHistory.class));
    }

    private void assertNoSideEffects() {
        verifyNoInteractions(generator, downloads);
        assertEquals(0, cancellationCalls.get());
        assertEquals(1, cancellations.size());
    }
}
