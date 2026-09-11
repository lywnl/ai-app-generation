package com.lyw.appgeneration.service;

import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.mapper.UserMapper;
import com.lyw.appgeneration.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserDisplayIdentityServiceTest {
    private final UserMapper mapper = mock(UserMapper.class);
    private final RandomGenerator random = mock(RandomGenerator.class);
    private final UserDisplayIdentityService identities = new UserDisplayIdentityService(mapper, random);

    @Test
    void createsEightDigitNameAndFixedAvatarWithoutChangingAccountOrRole() {
        when(random.nextInt(100_000_000)).thenReturn(123);
        when(mapper.insertSelective(any(User.class))).thenReturn(1);
        User user = User.builder().userAccount("admin-account").userRole("admin")
                .userPassword("unchanged-hash").userName("旧管理员").userAvatar("https://old.example/avatar").build();

        assertTrue(identities.createUser(user));

        assertEquals("用户_00000123", user.getUserName());
        assertEquals("/api/default-user-avatar.jpg", user.getUserAvatar());
        assertEquals("admin-account", user.getUserAccount());
        assertEquals("admin", user.getUserRole());
        assertEquals("unchanged-hash", user.getUserPassword());
    }

    @Test
    void existingNameIncludingDeletedUserTriggersAnotherCandidate() {
        when(random.nextInt(100_000_000)).thenReturn(123, 456);
        when(mapper.countDisplayNameIncludingDeleted("用户_00000123")).thenReturn(1L);
        when(mapper.insertSelective(any(User.class))).thenReturn(1);
        User user = new User();
        assertTrue(identities.createUser(user));
        assertEquals("用户_00000456", user.getUserName());
        verify(mapper, times(1)).insertSelective(user);
    }

    @Test
    void concurrentUniqueConstraintConflictRetriesNameButNotAccountConflict() {
        when(random.nextInt(100_000_000)).thenReturn(123, 456);
        when(mapper.insertSelective(any(User.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key user.uk_userName"))
                .thenReturn(1);
        User user = new User();
        assertTrue(identities.createUser(user));
        assertEquals("用户_00000456", user.getUserName());
        verify(mapper, times(2)).insertSelective(user);
        reset(mapper);
        when(mapper.insertSelective(any(User.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key user.uk_userAccount"));
        assertThrows(DuplicateKeyException.class, () -> identities.createUser(new User()));
        verify(mapper, times(1)).insertSelective(any(User.class));
    }

    @Test
    void collisionRetriesAreBounded() {
        when(mapper.countDisplayNameIncludingDeleted(anyString())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> identities.createUser(new User()));
        verify(random, times(20)).nextInt(100_000_000);
        verify(mapper, never()).insertSelective(any(User.class));
    }

    @Test
    void updatePreservesGeneratedIdentityAndAcceptsOnlyMatchingValues() {
        User existing = User.builder().userName("用户_00000123").build();
        assertDoesNotThrow(() -> identities.validateUpdate(new User(), existing));
        User change = User.builder().userName("用户_00000123")
                .userAvatar(UserDisplayIdentityService.DEFAULT_AVATAR).build();
        assertDoesNotThrow(() -> identities.validateUpdate(change, existing));
        for (String name : new String[]{"无名", "用户_1234567", "用户_123456789", "用户_00000456"}) {
            change.setUserName(name);
            assertThrows(BusinessException.class, () -> identities.validateUpdate(change, existing));
        }
        change.setUserName(null);
        change.setUserAvatar("https://old.example/avatar");
        assertThrows(BusinessException.class, () -> identities.validateUpdate(change, existing));
    }
}
