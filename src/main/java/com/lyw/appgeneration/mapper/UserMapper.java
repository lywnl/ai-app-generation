package com.lyw.appgeneration.mapper;

import com.mybatisflex.core.BaseMapper;
import com.lyw.appgeneration.model.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 映射层。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public interface UserMapper extends BaseMapper<User> {

    /** 昵称唯一索引覆盖逻辑删除用户，查重时不能自动过滤这些记录。 */
    @Select("SELECT COUNT(*) FROM `user` WHERE userName = #{userName}")
    long countDisplayNameIncludingDeleted(@Param("userName") String userName);
}
