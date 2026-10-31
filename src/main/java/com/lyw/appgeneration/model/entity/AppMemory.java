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
 * L2 跨 app 用户长期记忆 实体类。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("app_memory")
public class AppMemory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /** 用户 id */
    @Column("userId")
    private Long userId;

    /** 来源 app 溯源(USER_PREFERENCE 可空) */
    @Column("appId")
    private Long appId;

    /** 记忆类型(二期固定 USER_PREFERENCE) */
    private String type;

    /** 固定类别：语言偏好/视觉风格/技术栈倾向/体验偏好；类别不是唯一键。 */
    private String name;

    /** 偏好内容 */
    private String content;

    /** 稳定属性键；同一用户同一属性只能有一个生效版本。 */
    @Column("memoryKey")
    private String memoryKey;

    /** 属性名称，供抽取模型复用；不限制偏好的具体取值。 */
    @Column("attributeName")
    private String attributeName;

    /** 乐观版本，防止过期抽取结果覆盖新的状态。 */
    private Long revision;

    /** 替代当前历史版本的新记忆 ID。 */
    @Column("supersededBy")
    private Long supersededBy;

    /** 从候选晋升时关联原候选。 */
    @Column("originCandidateId")
    private Long originCandidateId;

    /** 撤销证据的有序 ID；可能是聊天回合或可追溯的手动操作，保留旧列名。 */
    @Column("revokedByTurnId")
    private Long revokedByTurnId;

    /** 旧系统无法还原明细的证据基线，不能伪造为新的证据行。 */
    @Column("legacyEvidenceCount")
    private Integer legacyEvidenceCount;

    @Column("legacyEvidenceTurnId")
    private Long legacyEvidenceTurnId;

    /** 证据类型：EXPLICIT / IMPLICIT */
    @Column("evidenceType")
    private String evidenceType;

    /** 正式状态：ACTIVE / SUPERSEDED / REVOKED；候选存放在独立表。 */
    private String status;

    /** 旧基线加去重后的证据事件数；手动操作与聊天回合明确区分来源。 */
    @Column("evidenceCount")
    private Integer evidenceCount;

    /** 已累计证据中的最大有序事件 ID，保留旧列名以兼容历史数据。 */
    @Column("lastEvidenceTurnId")
    private Long lastEvidenceTurnId;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
