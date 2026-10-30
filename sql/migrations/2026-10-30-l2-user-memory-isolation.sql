-- L2 正式/候选隔离与版本迁移；适用于 MySQL 8.0.40。
-- 执行前停止所有旧版 L2 写入并独立备份 app_memory 和抽取游标。
-- 本脚本先预检并展示问题清单，未知内容或互斥组合会 SIGNAL 停止，不修改原表。
-- ALTER TABLE / CREATE TABLE / RENAME TABLE 会隐式提交：使用影子表构建后原子改名切换。
-- 原表完整保留为 app_memory_legacy_20261030；不伪造旧证据明细，不自动清理历史。
-- 回退需先停写、导出新正式/候选/证据表，再人工恢复旧应用与旧快照；不能直接降级二进制。
-- 请使用 mysql 客户端执行（支持 DELIMITER），不要加 --force。

DROP PROCEDURE IF EXISTS migrate_l2_user_memory_v3;
DELIMITER $$
CREATE PROCEDURE migrate_l2_user_memory_v3()
migration: BEGIN
    DECLARE v_count BIGINT DEFAULT 0;
    DECLARE v_max_id BIGINT DEFAULT 0;
    DECLARE v_extra BIGINT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;
    IF DATABASE() IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '请先选择目标数据库';
    END IF;
    SELECT COUNT(*) INTO v_count FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'app_memory' AND column_name = 'memoryKey';
    IF v_count = 1 THEN
        SELECT COUNT(*) INTO v_count FROM information_schema.tables
          WHERE table_schema = DATABASE() AND table_name IN ('app_memory_candidate', 'app_memory_evidence');
        IF v_count <> 2 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '新结构不完整，请检查候选和证据表';
        END IF;
        SELECT COUNT(*) INTO v_count FROM information_schema.statistics
          WHERE table_schema = DATABASE() AND table_name = 'app_memory' AND index_name = 'uk_active_memory';
        IF v_count <> 4 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '正式生效唯一索引不完整';
        END IF;
        SELECT '已迁移，保留全部正式版本、候选和证据' AS result;
        LEAVE migration;
    END IF;
    SELECT status, evidenceType, COUNT(*) AS rowCount FROM app_memory GROUP BY status, evidenceType;
    SELECT COUNT(*) INTO v_count FROM app_memory
      WHERE type <> 'USER_PREFERENCE' OR status NOT IN ('ACTIVE','CANDIDATE')
         OR evidenceType NOT IN ('EXPLICIT','IMPLICIT') OR evidenceCount < 1
         OR id <= 0 OR userId IS NULL OR userId <= 0 OR isDelete NOT IN (0,1);
    IF v_count > 0 THEN
        SELECT id, userId, type, name, status, evidenceType, evidenceCount, isDelete FROM app_memory
          WHERE type <> 'USER_PREFERENCE' OR status NOT IN ('ACTIVE','CANDIDATE')
             OR evidenceType NOT IN ('EXPLICIT','IMPLICIT') OR evidenceCount < 1
             OR id <= 0 OR userId IS NULL OR userId <= 0 OR isDelete NOT IN (0,1);
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '旧记忆结构或状态无效，先核对报告';
    END IF;
    DROP TEMPORARY TABLE IF EXISTS l2_legacy_catalog;
    CREATE TEMPORARY TABLE l2_legacy_catalog (
        position INT PRIMARY KEY, category VARCHAR(128), valueText VARCHAR(128),
        memoryKey VARCHAR(128), attributeName VARCHAR(128)
    ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    INSERT INTO l2_legacy_catalog VALUES
        (1, '语言偏好', '简体中文', 'ui.default_language', '页面默认语言'),
        (2, '语言偏好', '繁体中文', 'ui.default_language', '页面默认语言'),
        (3, '语言偏好', '英文', 'ui.default_language', '页面默认语言'),
        (4, '语言偏好', '中英双语', 'ui.default_language', '页面默认语言'),
        (5, '视觉风格', '深色', 'ui.theme', '明暗主题'),
        (6, '视觉风格', '浅色', 'ui.theme', '明暗主题'),
        (7, '视觉风格', '极简', 'ui.visual_style', '视觉样式'),
        (8, '视觉风格', '扁平化', 'ui.visual_style', '视觉样式'),
        (9, '技术栈倾向', 'Vue 3', 'tech.frontend.framework', '前端框架'),
        (10, '技术栈倾向', 'React', 'tech.frontend.framework', '前端框架'),
        (11, '技术栈倾向', 'TypeScript', 'tech.language', '开发语言'),
        (12, '技术栈倾向', 'JavaScript', 'tech.language', '开发语言'),
        (13, '技术栈倾向', 'Tailwind CSS', 'tech.css_framework', 'CSS 框架'),
        (14, '技术栈倾向', 'Spring Boot', 'tech.backend.framework', '后端框架'),
        (15, '交互习惯', '键盘优先', 'ui.input_method', '输入方式'),
        (16, '交互习惯', '移动端优先', 'ui.device_priority', '设备适配优先级'),
        (17, '交互习惯', '桌面端优先', 'ui.device_priority', '设备适配优先级'),
        (18, '交互习惯', '简洁交互', 'ui.interaction_complexity', '交互复杂度'),
        (19, '交互习惯', '减少动效', 'ui.motion', '动效偏好');
    -- 按旧目录的确切片段匹配；“中英双语”作为一项保留，不按汉字或逗号拆分。
    DROP TEMPORARY TABLE IF EXISTS l2_legacy_parts;
    CREATE TEMPORARY TABLE l2_legacy_parts AS
      SELECT m.id AS oldId, c.position, c.memoryKey, c.attributeName, c.valueText
      FROM app_memory m JOIN l2_legacy_catalog c ON m.name = c.category
      AND FIND_IN_SET(c.valueText, REPLACE(m.content, '、', ',')) > 0;
    SELECT COUNT(*) INTO v_count FROM app_memory m LEFT JOIN (
      SELECT oldId, GROUP_CONCAT(valueText ORDER BY position SEPARATOR '、') AS rebuilt
      FROM l2_legacy_parts GROUP BY oldId
    ) p ON m.id = p.oldId WHERE p.oldId IS NULL OR BINARY p.rebuilt <> BINARY m.content;
    IF v_count > 0 THEN
        SELECT m.id, m.name, m.content AS originalContent, p.rebuilt AS mappedContent
        FROM app_memory m LEFT JOIN (
          SELECT oldId, GROUP_CONCAT(valueText ORDER BY position SEPARATOR '、') AS rebuilt
          FROM l2_legacy_parts GROUP BY oldId
        ) p ON m.id = p.oldId WHERE p.oldId IS NULL OR BINARY p.rebuilt <> BINARY m.content;
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '存在无法无歧义映射的旧内容，保留原表并人工确认';
    END IF;
    SELECT COUNT(*) INTO v_count FROM (
      SELECT oldId, memoryKey FROM l2_legacy_parts
      WHERE memoryKey IN ('ui.default_language','ui.theme','tech.frontend.framework','ui.device_priority')
      GROUP BY oldId, memoryKey HAVING COUNT(*) > 1
    ) conflicting;
    IF v_count > 0 THEN
        SELECT oldId, memoryKey, GROUP_CONCAT(valueText ORDER BY position SEPARATOR '、') AS conflictingValues
        FROM l2_legacy_parts
        WHERE memoryKey IN ('ui.default_language','ui.theme','tech.frontend.framework','ui.device_priority')
        GROUP BY oldId, memoryKey HAVING COUNT(*) > 1;
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '存在同属性互斥组合，先明确原意后迁移';
    END IF;
    DROP TEMPORARY TABLE IF EXISTS l2_legacy_groups;
    CREATE TEMPORARY TABLE l2_legacy_groups AS
      SELECT oldId, memoryKey, MAX(attributeName) AS attributeName, MIN(position) AS position,
        GROUP_CONCAT(valueText ORDER BY position SEPARATOR '、') AS content,
        ROW_NUMBER() OVER (PARTITION BY oldId ORDER BY MIN(position)) AS partNumber
      FROM l2_legacy_parts GROUP BY oldId, memoryKey;
    SELECT COALESCE(MAX(id), 0) INTO v_max_id FROM app_memory;
    SELECT COUNT(*) INTO v_extra FROM l2_legacy_groups WHERE partNumber > 1;
    IF v_max_id > 9223372036854775807 - v_extra THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '拆分ID超过有符号BIGINT范围，停止迁移';
    END IF;
    SELECT COUNT(*) INTO v_count FROM information_schema.tables
      WHERE table_schema = DATABASE() AND table_name = 'app_memory_legacy_20261030';
    IF v_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '旧快照表名已存在，拒绝覆盖备份';
    END IF;
-- L2 正式记忆及历史：同一属性只允许一个生效版本
CREATE TABLE IF NOT EXISTS app_memory_l2_v3_stage (
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

    CREATE TABLE IF NOT EXISTS app_memory_migration_20261030 (
        oldId BIGINT NOT NULL, memoryKey VARCHAR(128) NOT NULL, newId BIGINT NOT NULL,
        targetKind VARCHAR(16) NOT NULL, originalContent TEXT NOT NULL, migratedContent TEXT NOT NULL,
        PRIMARY KEY (oldId, memoryKey), UNIQUE KEY uk_migration_new_id (newId)
    ) COMMENT 'L2迁移ID与拆分内容映射，保留用于核验和回退' COLLATE = utf8mb4_unicode_ci;
    START TRANSACTION;
    INSERT IGNORE INTO app_memory_migration_20261030
      SELECT g.oldId, g.memoryKey, g.oldId,
        IF(m.status = 'ACTIVE', 'FORMAL', 'CANDIDATE'), m.content, g.content
      FROM l2_legacy_groups g JOIN app_memory m ON m.id = g.oldId WHERE g.partNumber = 1;
    INSERT IGNORE INTO app_memory_migration_20261030
      SELECT g.oldId, g.memoryKey, v_max_id + ROW_NUMBER() OVER (ORDER BY g.oldId, g.position),
        IF(m.status = 'ACTIVE', 'FORMAL', 'CANDIDATE'), m.content, g.content
      FROM l2_legacy_groups g JOIN app_memory m ON m.id = g.oldId WHERE g.partNumber > 1;
    INSERT IGNORE INTO app_memory_l2_v3_stage (
      id,userId,appId,type,name,memoryKey,attributeName,content,status,evidenceType,evidenceCount,
      lastEvidenceTurnId,legacyEvidenceCount,legacyEvidenceTurnId,revision,createTime,updateTime,isDelete)
    SELECT x.newId,m.userId,m.appId,m.type,IF(m.name='交互习惯','体验偏好',m.name),
      g.memoryKey,g.attributeName,g.content,'ACTIVE',m.evidenceType,m.evidenceCount,
      m.lastEvidenceTurnId,m.evidenceCount,m.lastEvidenceTurnId,1,m.createTime,m.updateTime,m.isDelete
    FROM app_memory m JOIN l2_legacy_groups g ON g.oldId=m.id
    JOIN app_memory_migration_20261030 x ON x.oldId=g.oldId AND x.memoryKey=g.memoryKey
    WHERE m.status='ACTIVE';
    INSERT IGNORE INTO app_memory_candidate (
      id,userId,appId,type,name,memoryKey,attributeName,content,contentHash,status,evidenceType,evidenceCount,
      lastEvidenceTurnId,legacyEvidenceCount,legacyEvidenceTurnId,revision,createTime,updateTime,isDelete)
    SELECT x.newId,m.userId,m.appId,m.type,IF(m.name='交互习惯','体验偏好',m.name),
      g.memoryKey,g.attributeName,g.content,SHA2(g.content,256),'CANDIDATE',m.evidenceType,m.evidenceCount,
      m.lastEvidenceTurnId,m.evidenceCount,m.lastEvidenceTurnId,1,m.createTime,m.updateTime,m.isDelete
    FROM app_memory m JOIN l2_legacy_groups g ON g.oldId=m.id
    JOIN app_memory_migration_20261030 x ON x.oldId=g.oldId AND x.memoryKey=g.memoryKey
    WHERE m.status='CANDIDATE';
    -- IGNORE 仅允许已核验的重入行；逐字段核验，不能静默丢弃约束冲突或旧值。
    SELECT COUNT(*) INTO v_count FROM l2_legacy_groups g JOIN app_memory m ON m.id=g.oldId
      LEFT JOIN app_memory_migration_20261030 x ON x.oldId=g.oldId AND x.memoryKey=g.memoryKey
      LEFT JOIN app_memory_l2_v3_stage f ON f.id=x.newId AND m.status='ACTIVE'
      LEFT JOIN app_memory_candidate c ON c.id=x.newId AND m.status='CANDIDATE'
      WHERE x.newId IS NULL OR NOT (BINARY g.content <=> BINARY COALESCE(f.content,c.content))
        OR NOT (m.userId <=> COALESCE(f.userId,c.userId))
        OR NOT (m.appId <=> COALESCE(f.appId,c.appId))
        OR NOT (g.memoryKey <=> COALESCE(f.memoryKey,c.memoryKey))
        OR NOT (g.attributeName <=> COALESCE(f.attributeName,c.attributeName))
        OR NOT (m.type <=> COALESCE(f.type,c.type))
        OR NOT (IF(m.name='交互习惯','体验偏好',m.name) <=> COALESCE(f.name,c.name))
        OR COALESCE(f.revision,c.revision) <> 1
        OR NOT (m.createTime <=> COALESCE(f.createTime,c.createTime))
        OR NOT (m.updateTime <=> COALESCE(f.updateTime,c.updateTime))
        OR (c.id IS NOT NULL AND BINARY c.contentHash <> BINARY SHA2(g.content,256))
        OR NOT (m.status <=> COALESCE(f.status,c.status))
        OR NOT (m.evidenceType <=> COALESCE(f.evidenceType,c.evidenceType))
        OR NOT (m.evidenceCount <=> COALESCE(f.legacyEvidenceCount,c.legacyEvidenceCount))
        OR NOT (m.evidenceCount <=> COALESCE(f.evidenceCount,c.evidenceCount))
        OR NOT (m.lastEvidenceTurnId <=> COALESCE(f.lastEvidenceTurnId,c.lastEvidenceTurnId))
        OR NOT (m.lastEvidenceTurnId <=> COALESCE(f.legacyEvidenceTurnId,c.legacyEvidenceTurnId))
        OR NOT (m.isDelete <=> COALESCE(f.isDelete,c.isDelete));
    IF v_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '迁移数据验收失败，事务已回滚，原表未切换';
    END IF;
    SELECT (SELECT COUNT(*) FROM app_memory_l2_v3_stage) + (SELECT COUNT(*) FROM app_memory_candidate)
      - (SELECT COUNT(*) FROM l2_legacy_groups) INTO v_count;
    IF v_count <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '迁移行数不一致，拒绝切换';
    END IF;
    COMMIT;
    RENAME TABLE app_memory TO app_memory_legacy_20261030, app_memory_l2_v3_stage TO app_memory;
    SELECT '完成：旧表完整保留，候选未自动激活，旧证据只保存真实基线' AS result;
    SELECT * FROM app_memory_migration_20261030 ORDER BY oldId, memoryKey;
END$$
DELIMITER ;
CALL migrate_l2_user_memory_v3();
DROP PROCEDURE IF EXISTS migrate_l2_user_memory_v3;
