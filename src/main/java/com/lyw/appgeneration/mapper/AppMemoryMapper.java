package com.lyw.appgeneration.mapper;

import com.lyw.appgeneration.model.entity.AppMemory;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * L2 用户长期记忆 映射层。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public interface AppMemoryMapper extends BaseMapper<AppMemory> {

    @Update("UPDATE app_memory SET appId = NULL WHERE appId = #{appId}")
    int unlinkAppId(@Param("appId") Long appId);
}
