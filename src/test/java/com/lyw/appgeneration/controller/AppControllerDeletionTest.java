package com.lyw.appgeneration.controller;

import com.lyw.appgeneration.common.DeleteRequest;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppControllerDeletionTest {

    private final AppService appService = mock(AppService.class);
    private final UserService userService = mock(UserService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private AppController controller;

    @BeforeEach
    void setUp() {
        controller = new AppController();
        ReflectionTestUtils.setField(controller, "appService", appService);
        ReflectionTestUtils.setField(controller, "userService", userService);
    }

    @Test
    void userControllerOnlyGetsLoginUserAndDelegatesControlledDelete() {
        User user = User.builder().id(9L).build();
        DeleteRequest deletion = new DeleteRequest();
        deletion.setId(7L);
        when(userService.getLoginUser(request)).thenReturn(user);
        when(appService.deleteApp(7L, user)).thenReturn(true);

        assertTrue(controller.deleteApp(deletion, request).getData());

        verify(appService).deleteApp(7L, user);
        verify(appService, never()).getById(7L);
        verify(appService, never()).removeById(7L);
    }

    @Test
    void adminControllerDelegatesSameControlledService() {
        User admin = User.builder().id(1L).userRole("admin").build();
        DeleteRequest deletion = new DeleteRequest();
        deletion.setId(7L);
        when(userService.getLoginUser(request)).thenReturn(admin);
        when(appService.deleteApp(7L, admin)).thenReturn(true);

        assertTrue(controller.deleteAppByAdmin(deletion, request).getData());

        verify(appService).deleteApp(7L, admin);
        verify(appService, never()).getById(7L);
        verify(appService, never()).removeById(7L);
    }
}
