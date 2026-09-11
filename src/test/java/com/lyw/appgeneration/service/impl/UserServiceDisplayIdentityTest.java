package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.mapper.UserMapper;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.service.UserDisplayIdentityService;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static com.lyw.appgeneration.constants.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceDisplayIdentityTest {
    private final UserMapper mapper = mock(UserMapper.class);
    private final UserServiceImpl service = spy(new UserServiceImpl());

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "userMapper", mapper);
        ReflectionTestUtils.setField(service, "displayIdentityService", new UserDisplayIdentityService(mapper));
    }

    @Test
    void registrationPersistsGeneratedIdentityWithUnchangedAccountPasswordAndRoleRules() {
        when(mapper.insertSelective(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            saved.setId(7L);
            assertEquals("fixture-account", saved.getUserAccount());
            assertEquals(service.getEncryptPassword("test-password"), saved.getUserPassword());
            assertEquals("user", saved.getUserRole());
            assertTrue(saved.getUserName().matches("用户_[0-9]{8}"));
            assertEquals(UserDisplayIdentityService.DEFAULT_AVATAR, saved.getUserAvatar());
            return 1;
        });
        assertEquals(7L, service.userRegister("fixture-account", "test-password", "test-password"));
    }

    @Test
    void loginAndSessionRefreshUsePersistedNameWithoutRegeneration() {
        User user = User.builder().id(7L).userAccount("fixture-account").userName("用户_01234567")
                .userAvatar(UserDisplayIdentityService.DEFAULT_AVATAR).userRole("admin").build();
        when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(user);
        doReturn(user).when(service).getById(7L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals("用户_01234567", service.userLogin("fixture-account", "test-password", request).getUserName());
        assertEquals("用户_01234567", service.getLoginUser(request).getUserName());
        assertEquals("admin", service.getLoginUserVO(user).getUserRole());
        assertEquals("用户_01234567", service.getUserVO(user).getUserName());
        assertTrue(service.userLogout(request));
        assertNull(request.getSession().getAttribute(USER_LOGIN_STATE));
        verify(mapper, never()).insertSelective(any(User.class));
        verify(mapper, never()).countDisplayNameIncludingDeleted(any());
    }
}
