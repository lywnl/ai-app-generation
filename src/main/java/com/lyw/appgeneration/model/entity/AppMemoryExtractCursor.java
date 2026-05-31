package com.lyw.appgeneration.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * L2 抽取游标(每 app 一行) 实体类。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_memory_extract_cursor")
public class AppMemoryExtractCursor implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("appId")
    private Long appId;

    @Column("userId")
    private Long userId;

    /** L2 已抽取到的 chat_history.id 游标 */
    @Column("lastExtractedId")
    private Long lastExtractedId;

    /** 连续失败计数(circuit breaker) */
    @Column("failCount")
    private Integer failCount;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
