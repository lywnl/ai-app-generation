package com.lyw.appgeneration.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.model.dto.UserRegisterRequest;
import com.lyw.appgeneration.model.enums.UserRoleEnum;
import com.lyw.appgeneration.model.vo.LoginUserVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.mapper.UserMapper;
import com.lyw.appgeneration.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

import static com.lyw.appgeneration.constants.UserConstant.USER_LOGIN_STATE;

/**
 * 用户 服务层实现。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>  implements UserService{

    @Resource
    private UserMapper userMapper;

    @Override
    public Long userRegister(String userAccount, String userPassword, String checkPassword) {
        //1.参数校验
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号长度不能小于4");
        }

        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码长度不能小于8");
        }

        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        //2.查询用户是否存在
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(User::getUserAccount, userAccount);
        long count = userMapper.selectCountByQuery(queryWrapper);

        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户已存在");
        }

        //3.密码加密
        String encryptPassword = getEncryptPassword(userPassword);
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        user.setUserName("无名");
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean save = save(user);
        if (!save) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败");
        }

        return user.getId();
    }

    @Override
    public String getEncryptPassword(String password) {
        final String SALT = "lyw";
        return DigestUtils.md5DigestAsHex((password + SALT).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }

        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);

        return loginUserVO;
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        //1.参数校验
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号长度不能小于4");
        }

        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码长度不能小于8");
        }

        //2.加密密码
        String encryptPassword = getEncryptPassword(userPassword);

        //3.查询用户是否存在
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(User::getUserAccount, userAccount);
        queryWrapper.eq(User::getUserPassword, encryptPassword);
        User user = userMapper.selectOneByQuery(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }

        //4.如果用户存在 记录用户的登录状态
        request.getSession().setAttribute(USER_LOGIN_STATE, user);

        return getLoginUserVO(user);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        //先判断用户是否登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;

        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        //从数据库查询 确保最新数据
        currentUser = getById(currentUser.getId());
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        return currentUser;
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        //先判断用户是否登录
        Object object = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (object == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        //移除登录状态
        request.getSession().removeAttribute(USER_LOGIN_STATE);

        return true;
    }

}
