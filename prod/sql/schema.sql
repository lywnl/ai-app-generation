create database if not exists ai_app_generation;


use ai_app_generation;

-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           not null comment '持久化展示昵称：用户_加8位数字',
    userAvatar   varchar(1024) default '/api/default-user-avatar.jpg' not null comment '统一用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    UNIQUE KEY uk_userName (userName)
    ) comment '用户' collate = utf8mb4_unicode_ci;



-- 应用表
create table app
(
    id           bigint auto_increment comment 'id' primary key,
    appName      varchar(256)                       null comment '应用名称',
    cover        varchar(512)                       null comment '应用封面',
    initPrompt   text                               null comment '应用初始化的 prompt',
    codeGenType  varchar(64)                        null comment '代码生成类型（枚举）',
    deployKey    varchar(64)                        null comment '部署标识',
    deployedTime datetime                           null comment '部署时间',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId)            -- 提升基于用户 ID 的查询性能
) comment '应用' collate = utf8mb4_unicode_ci;


-- 对话历史表
create table chat_history
(
    id          bigint auto_increment comment 'id' primary key,
    message       MEDIUMTEXT                         not null comment '展示消息',
    memoryMessage MEDIUMTEXT                         null comment '模型记忆投影',
    memoryOutcome varchar(32)                        null comment '记忆投影结果',
    messageType   varchar(32)                        not null comment 'user/ai',
    appId       bigint                             not null comment '应用id',
    userId      bigint                             not null comment '创建用户id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    INDEX idx_appId (appId),                       -- 提升基于应用的查询性能
    INDEX idx_createTime (createTime),             -- 提升基于时间的查询性能
    INDEX idx_appId_createTime (appId, createTime) -- 游标查询核心索引
) comment '对话历史' collate = utf8mb4_unicode_ci;


-- L1 滚动摘要表（分层记忆一期，每 app 一行）
create table if not exists app_memory_summary
(
    id               bigint                             not null comment '主键（snowflake）' primary key,
    appId            bigint                             not null comment '应用id',
    summary          MEDIUMTEXT                         null comment '5段模板摘要内容',
    lastSummarizedId bigint   default 0                 not null comment '已覆盖到的 chat_history.id 游标',
    summaryTokens    int      default 0                 not null comment '摘要估算token',
    failCount        int      default 0                 not null comment '连续失败计数',
    nextRetryTime    datetime                           null comment '下一次允许后台重试时间',
    createTime       datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime       datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete         tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_appId (appId) -- 每个应用仅一行摘要
) comment 'L1 滚动摘要（每app一行）' collate = utf8mb4_unicode_ci;


-- L2 正式记忆及历史：同一属性只允许一个生效版本
CREATE TABLE IF NOT EXISTS app_memory (
    id BIGINT NOT NULL COMMENT '后端生成的记录ID' PRIMARY KEY,
    userId BIGINT NOT NULL COMMENT '用户ID',
    appId BIGINT NULL COMMENT '来源应用，删除应用只解绑来源',
    type VARCHAR(32) NOT NULL DEFAULT 'USER_PREFERENCE' COMMENT '记忆类型',
    name VARCHAR(128) NOT NULL COMMENT '语言偏好/视觉风格/技术栈倾向/体验偏好',
    memoryKey VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定属性键',
    attributeName VARCHAR(128) NOT NULL COMMENT '属性名称，不含取值',
    content TEXT NOT NULL COMMENT '自由内容，每条表达独立偏好',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUPERSEDED/REVOKED',
    supersededBy BIGINT NULL COMMENT '替代本版本的新正式ID',
    originCandidateId BIGINT NULL COMMENT '晋升来源候选ID',
    evidenceType VARCHAR(16) NOT NULL DEFAULT 'EXPLICIT' COMMENT 'EXPLICIT/IMPLICIT',
    evidenceCount INT NOT NULL DEFAULT 1 COMMENT '旧证据基线加去重后的新证据数',
    lastEvidenceTurnId BIGINT NULL COMMENT '最大有效用户回合ID',
    legacyEvidenceCount INT NOT NULL DEFAULT 0 COMMENT '无法还原明细的旧证据基线',
    legacyEvidenceTurnId BIGINT NULL COMMENT '已知的旧证据回合，不重复计数',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '并发校验版本',
    revokedByTurnId BIGINT NULL COMMENT '明确撤销依据',
    createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除',
    activeSlot TINYINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' AND isDelete = 0 THEN 1 ELSE NULL END
    ) STORED,
    UNIQUE KEY uk_active_memory (userId, type, memoryKey, activeSlot),
    KEY idx_memory_recall (userId, type, status, isDelete, evidenceType, updateTime, id),
    KEY idx_memory_source (appId),
    CONSTRAINT ck_memory_status CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'REVOKED')),
    CONSTRAINT ck_memory_category CHECK (name IN ('语言偏好', '视觉风格', '技术栈倾向', '体验偏好')),
    CONSTRAINT ck_memory_evidence CHECK (evidenceType IN ('EXPLICIT', 'IMPLICIT') AND evidenceCount >= 0
        AND legacyEvidenceCount >= 0 AND evidenceCount >= legacyEvidenceCount AND revision >= 1)
) COMMENT 'L2 正式记忆及历史版本' COLLATE = utf8mb4_unicode_ci;

-- L2 候选：独立证据、独立状态，不参与生成模型召回
CREATE TABLE IF NOT EXISTS app_memory_candidate (
    id BIGINT NOT NULL COMMENT '后端生成的记录ID' PRIMARY KEY,
    userId BIGINT NOT NULL COMMENT '用户ID',
    appId BIGINT NULL COMMENT '来源应用，删除应用只解绑来源',
    type VARCHAR(32) NOT NULL DEFAULT 'USER_PREFERENCE' COMMENT '记忆类型',
    name VARCHAR(128) NOT NULL COMMENT '语言偏好/视觉风格/技术栈倾向/体验偏好',
    memoryKey VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定属性键',
    attributeName VARCHAR(128) NOT NULL COMMENT '属性名称，不含取值',
    content TEXT NOT NULL COMMENT '自由内容，每条表达独立偏好',
    contentHash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化内容SHA256',
    status VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE' COMMENT 'CANDIDATE/PROMOTED/REVOKED',
    promotedMemoryId BIGINT NULL COMMENT '晋升后的正式版本ID',
    evidenceType VARCHAR(16) NOT NULL DEFAULT 'EXPLICIT' COMMENT 'EXPLICIT/IMPLICIT',
    evidenceCount INT NOT NULL DEFAULT 1 COMMENT '旧证据基线加去重后的新证据数',
    lastEvidenceTurnId BIGINT NULL COMMENT '最大有效用户回合ID',
    legacyEvidenceCount INT NOT NULL DEFAULT 0 COMMENT '无法还原明细的旧证据基线',
    legacyEvidenceTurnId BIGINT NULL COMMENT '已知的旧证据回合，不重复计数',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '并发校验版本',
    revokedByTurnId BIGINT NULL COMMENT '明确撤销依据',
    createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除',
    pendingSlot TINYINT GENERATED ALWAYS AS (
        CASE WHEN status = 'CANDIDATE' AND isDelete = 0 THEN 1 ELSE NULL END
    ) STORED,
    UNIQUE KEY uk_pending_memory (userId, type, memoryKey, contentHash, pendingSlot),
    KEY idx_candidate_user (userId, type, status, isDelete, id),
    KEY idx_candidate_source (appId),
    CONSTRAINT ck_candidate_status CHECK (status IN ('CANDIDATE', 'PROMOTED', 'REVOKED')),
    CONSTRAINT ck_candidate_category CHECK (name IN ('语言偏好', '视觉风格', '技术栈倾向', '体验偏好')),
    CONSTRAINT ck_candidate_evidence CHECK (evidenceType IN ('EXPLICIT', 'IMPLICIT') AND evidenceCount >= 0
        AND legacyEvidenceCount >= 0 AND evidenceCount >= legacyEvidenceCount AND revision >= 1)
) COMMENT 'L2 独立候选及处理记录' COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app_memory_evidence (
    id BIGINT NOT NULL PRIMARY KEY,
    userId BIGINT NOT NULL,
    appId BIGINT NULL COMMENT '证据来源应用，删除只解绑',
    targetKind VARCHAR(16) NOT NULL COMMENT 'FORMAL/CANDIDATE',
    targetId BIGINT NOT NULL COMMENT '对应区域内的目标ID',
    turnId BIGINT NOT NULL COMMENT '完整回合中的用户消息ID',
    evidenceType VARCHAR(16) NOT NULL COMMENT 'EXPLICIT/IMPLICIT',
    createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_memory_evidence (userId, targetKind, targetId, turnId),
    KEY idx_evidence_source (appId),
    CONSTRAINT ck_evidence_kind CHECK (targetKind IN ('FORMAL', 'CANDIDATE')),
    CONSTRAINT ck_evidence_type CHECK (evidenceType IN ('EXPLICIT', 'IMPLICIT'))
) COMMENT 'L2 回合证据去重账本' COLLATE = utf8mb4_unicode_ci;

-- L2 抽取游标:per-app 一行
CREATE TABLE IF NOT EXISTS app_memory_extract_cursor (
    id              BIGINT   NOT NULL COMMENT '主键(snowflake)' PRIMARY KEY,
    appId           BIGINT   NOT NULL COMMENT '应用id',
    userId          BIGINT   NOT NULL COMMENT '用户id',
    lastExtractedId BIGINT   NOT NULL DEFAULT 0 COMMENT 'L2已抽取到的chat_history.id游标',
    failCount       INT      NOT NULL DEFAULT 0 COMMENT '连续失败计数',
    nextRetryTime   DATETIME NULL COMMENT '下一次允许后台重试时间',
    createTime      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete        TINYINT  NOT NULL DEFAULT 0 COMMENT '是否删除',
    UNIQUE KEY uk_appId (appId)
) COMMENT 'L2抽取游标(每app一行)' COLLATE = utf8mb4_unicode_ci;
