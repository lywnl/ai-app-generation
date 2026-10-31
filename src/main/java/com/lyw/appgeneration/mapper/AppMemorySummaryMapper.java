package com.lyw.appgeneration.mapper;

import com.mybatisflex.core.BaseMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * L1 滚动摘要 映射层。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public interface AppMemorySummaryMapper extends BaseMapper<AppMemorySummary> {

    /** 正文与全部覆盖字段只在预期修订仍有效时一起推进，不重写创建时间或删除状态。 */
    @Update("""
            UPDATE app_memory_summary
            SET summary=#{row.summary}, summaryTokens=#{row.summaryTokens},
                lastSummarizedId=#{row.lastSummarizedId}, summaryRevision=#{row.summaryRevision},
                coverageVersion=#{row.coverageVersion}, coverageState=#{row.coverageState},
                failCount=0, nextRetryTime=NULL, updateTime=#{row.updateTime}
            WHERE appId=#{row.appId} AND isDelete=0 AND summaryRevision=#{expectedRevision}
            """)
    int commitSnapshot(@Param("row") AppMemorySummary row, @Param("expectedRevision") long expectedRevision);

    /** 失败回调没有修改摘要与覆盖的权限；旧回调遇到更新后的修订时自然失效。 */
    @Update("""
            UPDATE app_memory_summary
            SET failCount=#{row.failCount}, nextRetryTime=#{row.nextRetryTime}, updateTime=#{row.updateTime}
            WHERE appId=#{row.appId} AND isDelete=0 AND summaryRevision=#{expectedRevision}
            """)
    int updateFailureMetadata(@Param("row") AppMemorySummary row, @Param("expectedRevision") long expectedRevision);

}
