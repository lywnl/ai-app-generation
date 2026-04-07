package com.lyw.appgeneration.service;

import com.lyw.appgeneration.model.dto.UserRegisterRequest;
import com.mybatisflex.core.service.IService;
import com.lyw.appgeneration.model.entity.User;

/**
 * 用户 服务层。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public interface UserService extends IService<User> {

    /**
     * 用户注册
     * @param userAccount
     * @param userPassword
     * @param checkPassword
     * @return
     */
    Long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 获取加密密码
     * @param password
     * @return
     */
    String getEncryptPassword(String password);
}
