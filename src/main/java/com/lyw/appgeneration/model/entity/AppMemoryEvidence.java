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

/** 正式与候选分别计数的证据账本；同一目标、同一回合只记一次。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_memory_evidence")
public class AppMemoryEvidence {
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;
    @Column("userId")
    private Long userId;
    @Column("appId")
    private Long appId;
    @Column("targetKind")
    private String targetKind;
    @Column("targetId")
    private Long targetId;
    @Column("turnId")
    private Long turnId;
    @Column("evidenceType")
    private String evidenceType;
    @Column("createTime")
    private LocalDateTime createTime;
}
