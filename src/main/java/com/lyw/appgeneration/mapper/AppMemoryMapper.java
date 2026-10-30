package com.lyw.appgeneration.mapper;

import com.lyw.appgeneration.model.entity.AppMemory;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;

/**
 * L2 用户长期记忆 映射层。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public interface AppMemoryMapper extends BaseMapper<AppMemory> {

    /** 写入事务按用户串行化，包括尚无正式记录的属性。 */
    @Select("SELECT id FROM `user` WHERE id = #{userId} AND isDelete = 0 FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockUser(@Param("userId") Long userId);

    @Update("UPDATE app_memory SET appId = NULL WHERE appId = #{appId}")
    int unlinkAppId(@Param("appId") Long appId);
}
