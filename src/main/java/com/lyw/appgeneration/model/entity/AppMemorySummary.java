package com.lyw.appgeneration.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.io.Serializable;
import java.time.LocalDateTime;

import java.io.Serial;

import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * L1 滚动摘要 实体类。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_memory_summary")
public class AppMemorySummary implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 应用id
     */
    @Column("appId")
    private Long appId;

    /**
     * 5段模板摘要内容
     */
    private String summary;

    /**
     * 已覆盖到的 chat_history.id 游标
     */
    @Column("lastSummarizedId")
    private Long lastSummarizedId;

    /**
     * 摘要估算token
     */
    @Column("summaryTokens")
    private Integer summaryTokens;

    /**
     * 连续失败计数（circuit breaker）
     */
    @Column("failCount")
    private Integer failCount;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;

}
