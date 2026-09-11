package com.lyw.appgeneration.controller;

import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.mapper.UserMapper;
import com.lyw.appgeneration.model.dto.user.UserUpdateRequest;
import com.lyw.appgeneration.model.dto.user.UserAddRequest;
import com.lyw.appgeneration.annotation.AuthCheck;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.service.UserDisplayIdentityService;
import com.lyw.appgeneration.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserDisplayIdentityControllerTest {
    @Test
    void adminCreationAndLegacySaveUseTheSameIdentityCreationPath() {
        UserController controller = new UserController();
        UserService users = mock(UserService.class);
        UserMapper mapper = mock(UserMapper.class);
        UserDisplayIdentityService identities = new UserDisplayIdentityService(mapper);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "displayIdentityService", identities);
        when(users.getEncryptPassword(any())).thenReturn("unchanged-default-password-hash");
        when(mapper.insertSelective(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            assertTrue(user.getUserName().matches("用户_[0-9]{8}"));
            assertEquals(UserDisplayIdentityService.DEFAULT_AVATAR, user.getUserAvatar());
            user.setId(7L);
            return 1;
        });
        when(users.createUser(any())).thenAnswer(call -> identities.createUser(call.getArgument(0)));
        UserAddRequest request = new UserAddRequest();
        request.setUserAccount("fixture-admin");
        request.setUserName("请求中的旧名称");
        request.setUserAvatar("https://old.example/photo");
        request.setUserRole("admin");
        assertEquals(7L, controller.addUser(request).getData());
        assertTrue(controller.save(User.builder().userName("旧名称").build()));
        verify(users, times(2)).createUser(any(User.class));
        verify(users, never()).save(any(User.class));
    }

    @Test
    void legacyUpdateRequiresAdminAuthorization() throws Exception {
        AuthCheck check = UserController.class.getMethod("update", User.class).getAnnotation(AuthCheck.class);
        assertNotNull(check);
        assertEquals("admin", check.mustRole());
    }

    @Test
    void invalidNameCannotBeSilentlyAcceptedByUpdateEndpoint() {
        UserController controller = new UserController();
        UserService users = mock(UserService.class);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "displayIdentityService",
                new UserDisplayIdentityService(mock(UserMapper.class)));
        when(users.getById(7L)).thenReturn(User.builder().id(7L)
                .userName("用户_12345678").userAvatar("/api/default-user-avatar.jpg").build());
        when(users.updateById(any(User.class))).thenReturn(true);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setId(7L);
        request.setUserName("无名");

        assertThrows(BusinessException.class, () -> controller.updateUser(request));
        verify(users, never()).updateById(any(User.class));
    }
}
