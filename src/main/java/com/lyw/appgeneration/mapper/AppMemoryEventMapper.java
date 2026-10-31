package com.lyw.appgeneration.mapper;

import com.lyw.appgeneration.model.entity.AppMemoryEvent;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AppMemoryEventMapper extends BaseMapper<AppMemoryEvent> {
    @Select("SELECT * FROM app_memory_event WHERE userId = #{userId} AND requestId = #{requestId} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    AppMemoryEvent findRequest(@Param("userId") long userId, @Param("requestId") String requestId);

    // 新记录与操作 ID 使用同一有序生成器，操作 ID 在创建新记忆前分配。
    // 管理功能接入前的记录作为既有快照展示，不编造过去的操作日志。
    String HISTORY = """
        WITH cutoff AS (
          SELECT COALESCE(MIN(id), 9223372036854775807) AS firstId
          FROM app_memory_event WHERE userId = #{userId}
        ), history AS (
          SELECT id, sourceType, changesJson, createTime FROM app_memory_event
          WHERE userId = #{userId} AND hasChanges = 1
          UNION ALL
          SELECT m.id, 'LEGACY', JSON_ARRAY(JSON_OBJECT(
            'action', 'SAVED', 'category', m.name, 'attributeName', m.attributeName,
            'beforeContent', NULL, 'afterContent', m.content,
            'targetId', CAST(m.id AS CHAR), 'targetKind', 'FORMAL', 'recordStatus', m.status)), m.createTime
          FROM app_memory m CROSS JOIN cutoff
          WHERE m.userId = #{userId} AND m.type = 'USER_PREFERENCE' AND m.isDelete = 0 AND m.id < cutoff.firstId
          UNION ALL
          SELECT c.id, 'LEGACY', JSON_ARRAY(JSON_OBJECT(
            'action', 'SAVED', 'category', c.name, 'attributeName', c.attributeName,
            'beforeContent', NULL, 'afterContent', c.content,
            'targetId', CAST(c.id AS CHAR), 'targetKind', 'CANDIDATE', 'recordStatus', c.status)), c.createTime
          FROM app_memory_candidate c CROSS JOIN cutoff
          WHERE c.userId = #{userId} AND c.type = 'USER_PREFERENCE' AND c.isDelete = 0 AND c.id < cutoff.firstId
        )
        """;

    @Select(HISTORY + "SELECT COUNT(*) FROM history")
    long countHistory(@Param("userId") long userId);

    @Select(HISTORY + "SELECT * FROM history ORDER BY createTime DESC, id DESC LIMIT #{offset}, #{pageSize}")
    List<AppMemoryEvent> historyPage(@Param("userId") long userId, @Param("offset") long offset,
                                    @Param("pageSize") int pageSize);

    @Update("UPDATE app_memory_event SET appId = NULL WHERE appId = #{appId}")
    int unlinkAppId(@Param("appId") Long appId);
}
