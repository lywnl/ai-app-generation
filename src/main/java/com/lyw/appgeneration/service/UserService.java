package com.lyw.appgeneration.service;

import com.lyw.appgeneration.model.dto.UserRegisterRequest;
import com.lyw.appgeneration.model.vo.LoginUserVO;
import com.mybatisflex.core.service.IService;
import com.lyw.appgeneration.model.entity.User;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;

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

    /**
     * 获取登录用户信息(脱敏后)
     * @param user
     * @return
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 用户登录
     * @param userAccount
     * @param userPassword
     * @param request
     * @return
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 获取当前登录用户
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 用户注销(退出登录)
     * @param request
     * @return
     */
    boolean userLogout(HttpServletRequest request);

}
