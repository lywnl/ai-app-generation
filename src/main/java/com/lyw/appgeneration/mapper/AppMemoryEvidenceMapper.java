package com.lyw.appgeneration.mapper;

import com.lyw.appgeneration.model.entity.AppMemoryEvidence;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AppMemoryEvidenceMapper extends BaseMapper<AppMemoryEvidence> {
    @Update("UPDATE app_memory_evidence SET appId = NULL WHERE appId = #{appId}")
    int unlinkAppId(@Param("appId") Long appId);
}
