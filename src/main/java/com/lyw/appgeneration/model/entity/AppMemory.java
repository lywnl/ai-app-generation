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
@Builder
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

    /** 偏好类别(去重键):语言偏好/视觉风格/技术栈倾向/交互习惯/其他 */
    private String name;

    /** 偏好内容 */
    private String content;

    /** 证据类型：EXPLICIT / IMPLICIT */
    @Column("evidenceType")
    private String evidenceType;

    /** 证据状态：CANDIDATE / ACTIVE */
    private String status;

    /** 已累计的不同完整回合证据数 */
    @Column("evidenceCount")
    private Integer evidenceCount;

    /** 已累计证据中的最大 User 回合 ID */
    @Column("lastEvidenceTurnId")
    private Long lastEvidenceTurnId;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
