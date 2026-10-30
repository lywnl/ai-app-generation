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

import java.time.LocalDateTime;

/** 未激活候选独立存储；候选的变化不得改写正式记忆。 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("app_memory_candidate")
public class AppMemoryCandidate {
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;
    @Column("userId")
    private Long userId;
    @Column("appId")
    private Long appId;
    private String type;
    private String name;
    @Column("memoryKey")
    private String memoryKey;
    @Column("attributeName")
    private String attributeName;
    private String content;
    @Column("contentHash")
    private String contentHash;
    /** CANDIDATE / PROMOTED / REVOKED。 */
    private String status;
    @Column("evidenceType")
    private String evidenceType;
    @Column("evidenceCount")
    private Integer evidenceCount;
    @Column("lastEvidenceTurnId")
    private Long lastEvidenceTurnId;
    @Column("legacyEvidenceCount")
    private Integer legacyEvidenceCount;
    @Column("legacyEvidenceTurnId")
    private Long legacyEvidenceTurnId;
    private Long revision;
    @Column("promotedMemoryId")
    private Long promotedMemoryId;
    @Column("revokedByTurnId")
    private Long revokedByTurnId;
    @Column("createTime")
    private LocalDateTime createTime;
    @Column("updateTime")
    private LocalDateTime updateTime;
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
