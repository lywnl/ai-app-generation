package com.lyw.appgeneration.mapper;

import com.lyw.appgeneration.model.entity.AppMemoryCandidate;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AppMemoryCandidateMapper extends BaseMapper<AppMemoryCandidate> {
    @Update("UPDATE app_memory_candidate SET appId = NULL WHERE appId = #{appId}")
    int unlinkAppId(@Param("appId") Long appId);
}
