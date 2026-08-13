package com.lyw.appgeneration.controller;

import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppControllerDownloadTest {

    private static final long APP_ID = 7L;
    private static final User LOGIN_USER = User.builder().id(9L).build();

    private final AppService appService = mock(AppService.class);
    private final UserService userService = mock(UserService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private AppController controller;

    @BeforeEach
    void setUp() {
        controller = new AppController();
        ReflectionTestUtils.setField(controller, "appService", appService);
        ReflectionTestUtils.setField(controller, "userService", userService);
        when(userService.getLoginUser(request)).thenReturn(LOGIN_USER);
    }

    @Test
    void controllerOnlyGetsLoginUserAndDelegatesDownload() {
        controller.downloadAppCode(APP_ID, request, response);

        verify(userService).getLoginUser(request);
        verify(appService).downloadApp(APP_ID, LOGIN_USER, response);
        verify(appService, never()).getById(APP_ID);
    }
}
