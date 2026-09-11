package com.lyw.appgeneration.service;

import com.lyw.appgeneration.mapper.UserMapper;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.constants.UserConstant;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.random.RandomGenerator;

/** 生成并校验用户对外展示身份；账号和权限字段不参与生成。 */
@Service
public class UserDisplayIdentityService {

    public static final String DEFAULT_AVATAR = "/api/default-user-avatar.jpg";
    private static final String NAME_PREFIX = "用户_";
    private static final int MAX_ATTEMPTS = 20;
    private final RandomGenerator random;
    private final UserMapper userMapper;

    @Autowired
    public UserDisplayIdentityService(UserMapper userMapper) {
        this(userMapper, new SecureRandom());
    }

    UserDisplayIdentityService(UserMapper userMapper, RandomGenerator random) {
        this.userMapper = userMapper;
        this.random = random;
    }

    /** 唯一索引负责并发兜底，仅昵称冲突才重新生成。 */
    public boolean createUser(User user) {
        user.setUserAvatar(DEFAULT_AVATAR);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String name = NAME_PREFIX + String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
            if (userMapper.countDisplayNameIncludingDeleted(name) != 0) {
                continue;
            }
            user.setUserName(name);
            try {
                // 忽略未设置字段，让时间和逻辑删除标记使用数据库默认值。
                return userMapper.insertSelective(user) > 0;
            } catch (DuplicateKeyException exception) {
                String message = exception.getMostSpecificCause().getMessage();
                if (message == null || !message.contains("uk_userName")) {
                    throw exception;
                }
            }
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成用户昵称失败，请重试");
    }

    /** 昵称一次生成后保持稳定；修改其他资料不能覆盖展示身份。 */
    public void validateUpdate(User change, User existing) {
        if (change.getUserName() != null
                && (!change.getUserName().matches(UserConstant.DISPLAY_NAME_PATTERN)
                || !change.getUserName().equals(existing.getUserName()))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户昵称由系统生成，不支持修改");
        }
        if (change.getUserAvatar() != null && !DEFAULT_AVATAR.equals(change.getUserAvatar())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户头像为统一头像，不支持修改");
        }
    }
}
