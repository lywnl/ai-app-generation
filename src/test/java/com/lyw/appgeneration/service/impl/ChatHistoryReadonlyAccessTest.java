package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.model.dto.app.ChatHistoryQueryRequest;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.AppService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatHistoryReadonlyAccessTest {
    private static final LocalDateTime DEPLOYED = LocalDateTime.of(2026, 11, 3, 10, 0);
    private final AppService apps = mock(AppService.class);
    private ChatHistoryServiceImpl service;
    private App app;
    private ChatHistory original;
    private Page<ChatHistory> storedPage;

    @BeforeEach
    void setUp() {
        service = spy(new ChatHistoryServiceImpl());
        ReflectionTestUtils.setField(service, "appService", apps);
        app = App.builder().id(7L).userId(9L).priority(99).isDelete(0)
                .deployKey("published").deployedTime(DEPLOYED).build();
        when(apps.getById(7L)).thenReturn(app);
        original = ChatHistory.builder().id(71L).appId(7L).userId(9L)
                .message("已完成页面").messageType("ai")
                .memoryMessage("内部工程投影").memoryOutcome(ChatMemoryOutcome.SUCCEEDED)
                .decisionEvidence("内部决策证据").createTime(DEPLOYED).updateTime(DEPLOYED)
                .isDelete(0).build();
        storedPage = new Page<>(List.of(original), 1, 10, 21);
        doReturn(storedPage).when(service).page(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void 普通用户可以查看精选且已部署应用且仅取得展示副本() {
        var result = service.listAppChatHistoryByPage(7L, 10, null, user(10, "user"));
        assertEquals(21, result.getTotalRow());
        assertEquals(10, result.getPageSize());
        assertEquals(3, result.getTotalPage());
        var shared = result.getRecords().getFirst();
        assertEquals("已完成页面", shared.getMessage());
        assertEquals("ai", shared.getMessageType());
        assertEquals(7L, shared.getAppId());
        assertEquals(9L, shared.getUserId());
        assertEquals(DEPLOYED, shared.getCreateTime());
        assertEquals(DEPLOYED, shared.getUpdateTime());
        assertNull(shared.getMemoryMessage());
        assertNull(shared.getMemoryOutcome());
        assertNull(shared.getDecisionEvidence());
        assertNull(shared.getIsDelete());
        assertNotSame(original, shared);
        assertEquals("内部工程投影", original.getMemoryMessage());
        assertEquals("内部决策证据", original.getDecisionEvidence());
        assertSame(original, storedPage.getRecords().getFirst());
        verify(service, never()).save(any(ChatHistory.class));
        verify(service, never()).updateById(any(ChatHistory.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1, 98, 100})
    void 已部署但不是精选时不可共享(Integer priority) {
        app.setPriority(priority);
        assertDenied(ErrorCode.NO_AUTH_ERROR, () ->
                service.listAppChatHistoryByPage(7L, 10, null, user(10, "user")));
        verify(service, never()).page(any(Page.class), any(QueryWrapper.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void 无有效部署标识的精选应用仍不可共享(String key) {
        app.setPriority(99);
        app.setDeployKey(key);
        assertDenied(ErrorCode.NO_AUTH_ERROR, () ->
                service.listAppChatHistoryByPage(7L, 10, null, user(10, "user")));
        verify(service, never()).page(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void 仅有部署标识不视为成功部署() {
        app.setDeployedTime(null);
        assertDenied(ErrorCode.NO_AUTH_ERROR, () ->
                service.listAppChatHistoryByPage(7L, 10, null, user(10, "user")));
    }

    @ParameterizedTest
    @CsvSource({"9,user", "10,admin"})
    void 作者和管理员仍能读取未部署应用并保持原接口数据(long id, String role) {
        app.setPriority(0);
        app.setDeployKey(null);
        app.setDeployedTime(null);
        var result = service.listAppChatHistoryByPage(7L, 10, null, user(id, role));
        assertSame(storedPage, result);
        assertEquals("内部工程投影", result.getRecords().getFirst().getMemoryMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 每页重新检查精选与部署状态(boolean removeFeatured) {
        service.listAppChatHistoryByPage(7L, 10, null, user(10, "user"));
        if (removeFeatured) app.setPriority(0);
        else app.setDeployedTime(null);
        assertDenied(ErrorCode.NO_AUTH_ERROR, () ->
                service.listAppChatHistoryByPage(7L, 10, DEPLOYED, user(10, "user")));
        verify(apps, times(2)).getById(7L);
        verify(service, times(1)).page(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void 查询保留应用边界和原时间游标() {
        service.listAppChatHistoryByPage(7L, 20, DEPLOYED, user(10, "user"));
        var request = ArgumentCaptor.forClass(ChatHistoryQueryRequest.class);
        verify(service).getQueryWrapper(request.capture());
        assertEquals(7L, request.getValue().getAppId());
        assertEquals(DEPLOYED, request.getValue().getLastCreateTime());
        var wrapper = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(service).page(any(Page.class), wrapper.capture());
        assertTrue(wrapper.getValue().toSQL().contains("appId"));
        assertTrue(wrapper.getValue().toSQL().contains("createTime"));

        when(apps.getById(8L)).thenReturn(App.builder().id(8L).userId(11L).build());
        assertDenied(ErrorCode.NO_AUTH_ERROR, () ->
                service.listAppChatHistoryByPage(8L, 10, null, user(10, "user")));
        verify(service, times(1)).page(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void 共享空历史保留正常分页形状() {
        doReturn(new Page<ChatHistory>(List.of(), 1, 10, 0))
                .when(service).page(any(Page.class), any(QueryWrapper.class));
        var result = service.listAppChatHistoryByPage(7L, 10, null, user(10, "user"));
        assertTrue(result.getRecords().isEmpty());
        assertEquals(0, result.getTotalRow());
    }

    @Test
    void 未登录和无效登录身份不查询应用() {
        for (User visitor : new User[]{null, new User(), user(0, "user"), user(-1, "admin")}) {
            assertDenied(ErrorCode.NOT_LOGIN_ERROR, () ->
                    service.listAppChatHistoryByPage(7L, 10, null, visitor));
        }
        verifyNoInteractions(apps);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51})
    void 非法分页大小不查询数据(int size) {
        assertDenied(ErrorCode.PARAMS_ERROR, () ->
                service.listAppChatHistoryByPage(7L, size, null, user(10, "user")));
        verifyNoInteractions(apps);
    }

    @Test
    void 无效应用标识不存在和删除不返回历史() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertDenied(ErrorCode.PARAMS_ERROR, () ->
                    service.listAppChatHistoryByPage(id, 10, null, user(10, "user")));
        }
        assertDenied(ErrorCode.NOT_FOUND_ERROR, () ->
                service.listAppChatHistoryByPage(88L, 10, null, user(10, "user")));
        app.setIsDelete(1);
        assertDenied(ErrorCode.NOT_FOUND_ERROR, () ->
                service.listAppChatHistoryByPage(7L, 10, null, user(9, "admin")));
        verify(service, never()).page(any(Page.class), any(QueryWrapper.class));
    }

    private static User user(long id, String role) {
        return User.builder().id(id).userRole(role).build();
    }

    private static void assertDenied(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code.getCode(), assertThrows(BusinessException.class, action).getCode());
    }
}
