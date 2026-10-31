package com.lyw.appgeneration.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/** 记忆变更记录；手动操作的请求去重与证据来源在同一事务中落地。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_memory_event")
public class AppMemoryEvent {
    @Id
    private Long id;
    @Column("userId")
    private Long userId;
    @Column("appId")
    private Long appId;
    @Column("sourceType")
    private String sourceType;
    @Column("requestId")
    private String requestId;
    @Column("requestHash")
    private String requestHash;
    @Column("changesJson")
    private String changesJson;
    @Column("resultJson")
    private String resultJson;
    @Column("hasChanges")
    private Boolean hasChanges;
    @Column("createTime")
    private LocalDateTime createTime;
}
