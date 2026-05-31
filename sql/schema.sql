create database if not exists ai_app_generation;


use ai_app_generation;

-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
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
    message     MEDIUMTEXT                         not null comment '消息',
    messageType varchar(32)                        not null comment 'user/ai',
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
    failCount        int      default 0                 not null comment '连续失败计数（circuit breaker）',
    createTime       datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime       datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete         tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_appId (appId) -- 每个应用仅一行摘要
) comment 'L1 滚动摘要（每app一行）' collate = utf8mb4_unicode_ci;


-- L2 用户偏好:结构化离散条目,每条一行
CREATE TABLE IF NOT EXISTS app_memory (
    id          BIGINT       NOT NULL COMMENT '主键(snowflake)' PRIMARY KEY,
    userId      BIGINT       NOT NULL COMMENT '用户id',
    appId       BIGINT       NULL     COMMENT '来源app溯源(USER_PREFERENCE可空)',
    type        VARCHAR(32)  NOT NULL DEFAULT 'USER_PREFERENCE' COMMENT '记忆类型(二期固定USER_PREFERENCE)',
    name        VARCHAR(128) NOT NULL COMMENT '偏好类别(去重键):语言偏好/视觉风格/技术栈倾向/交互习惯/其他',
    content     TEXT         NOT NULL COMMENT '偏好内容',
    createTime  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除',
    UNIQUE KEY uk_userId_type_name (userId, type, name)
) COMMENT 'L2 跨app用户长期记忆' COLLATE = utf8mb4_unicode_ci;

-- L2 抽取游标:per-app 一行
CREATE TABLE IF NOT EXISTS app_memory_extract_cursor (
    id              BIGINT   NOT NULL COMMENT '主键(snowflake)' PRIMARY KEY,
    appId           BIGINT   NOT NULL COMMENT '应用id',
    userId          BIGINT   NOT NULL COMMENT '用户id',
    lastExtractedId BIGINT   NOT NULL DEFAULT 0 COMMENT 'L2已抽取到的chat_history.id游标',
    failCount       INT      NOT NULL DEFAULT 0 COMMENT '连续失败计数(circuit breaker)',
    createTime      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete        TINYINT  NOT NULL DEFAULT 0 COMMENT '是否删除',
    UNIQUE KEY uk_appId (appId)
) COMMENT 'L2抽取游标(每app一行)' COLLATE = utf8mb4_unicode_ci;
