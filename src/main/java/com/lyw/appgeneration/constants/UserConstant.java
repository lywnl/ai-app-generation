package com.lyw.appgeneration.constants;

/**
 * 用户常量
 *
 * @author lyw
 */
public interface UserConstant {

    /**
     * 用户登录态键
     */
    String USER_LOGIN_STATE = "user_login";

    //  region 权限

    /**
     * 默认角色
     */
    String DEFAULT_ROLE = "user";

    /**
     * 管理员角色
     */
    String ADMIN_ROLE = "admin";

    String DISPLAY_NAME_PATTERN = "用户_[0-9]{8}";

    // endregion
}
