-- 长期记忆管理页：来源明确的证据、须确认候选、操作历史及请求去重。
-- 前置迁移：2026-10-30-l2-user-memory-isolation.sql。
-- 停止后端写入并备份相关记忆表后执行；本脚本不删除、覆盖或清理原有记忆。
-- DDL 会隐式提交；每一步均可重入，成功后须启动包含新字段的新后端。
DROP PROCEDURE IF EXISTS migrate_l2_memory_management;
DELIMITER $$
CREATE PROCEDURE migrate_l2_memory_management()
BEGIN
    DECLARE v_count INT DEFAULT 0;
    IF DATABASE() IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '请先选择目标数据库';
    END IF;
    SELECT COUNT(*) INTO v_count FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='app_memory' AND column_name='memoryKey';
    IF v_count <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '请先完成正式与候选分表迁移';
    END IF;
    SELECT COUNT(*) INTO v_count FROM information_schema.tables
      WHERE table_schema=DATABASE() AND table_name IN ('app_memory_candidate','app_memory_evidence');
    IF v_count <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '候选或证据表不存在，请先完成分表迁移';
    END IF;
    SELECT COUNT(*) INTO v_count FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='app_memory_candidate' AND column_name='requiresConfirmation';
    IF v_count=0 THEN
        ALTER TABLE app_memory_candidate ADD COLUMN requiresConfirmation TINYINT NOT NULL DEFAULT 0
          COMMENT '手动候选须明确采用，不随弱证据自动晋升';
    END IF;
    SELECT COUNT(*) INTO v_count FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='app_memory_evidence' AND column_name='sourceType';
    IF v_count=0 THEN
        ALTER TABLE app_memory_evidence ADD COLUMN sourceType VARCHAR(16) NOT NULL DEFAULT 'CHAT'
          COMMENT 'CHAT为真实用户回合，MANUAL为手动操作记录';
    END IF;
    SELECT COUNT(*) INTO v_count FROM information_schema.table_constraints
      WHERE constraint_schema=DATABASE() AND table_name='app_memory_candidate' AND constraint_name='ck_candidate_confirmation';
    IF v_count=0 THEN
        ALTER TABLE app_memory_candidate ADD CONSTRAINT ck_candidate_confirmation CHECK (requiresConfirmation IN (0,1));
    END IF;
    SELECT COUNT(*) INTO v_count FROM information_schema.table_constraints
      WHERE constraint_schema=DATABASE() AND table_name='app_memory_evidence' AND constraint_name='ck_evidence_source';
    IF v_count=0 THEN
        ALTER TABLE app_memory_evidence ADD CONSTRAINT ck_evidence_source CHECK (sourceType IN ('CHAT','MANUAL'));
    END IF;
-- 记忆操作历史与手动请求去重；与记忆、证据在同一事务提交
CREATE TABLE IF NOT EXISTS app_memory_event (
    id BIGINT NOT NULL PRIMARY KEY,
    userId BIGINT NOT NULL,
    appId BIGINT NULL COMMENT '聊天来源应用，删除应用时只解绑',
    sourceType VARCHAR(16) NOT NULL COMMENT 'CHAT/MANUAL，手动证据引用此记录ID',
    requestId CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '手动请求去重标识',
    requestHash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '规范操作参数摘要',
    changesJson JSON NOT NULL COMMENT '可展示的状态变更，不记录无关聊天正文',
    resultJson JSON NULL COMMENT '手动操作的原始返回结果，重试时复用',
    hasChanges TINYINT NOT NULL DEFAULT 0,
    createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_memory_request (userId, requestId),
    KEY idx_memory_event_history (userId, hasChanges, createTime, id),
    KEY idx_memory_event_user_id (userId, id),
    KEY idx_memory_event_source (appId),
    CONSTRAINT ck_memory_event_source CHECK (sourceType IN ('CHAT', 'MANUAL')),
    CONSTRAINT ck_memory_event_payload CHECK (JSON_TYPE(changesJson) = 'ARRAY' AND hasChanges IN (0, 1)),
    CONSTRAINT ck_memory_event_request CHECK (
        (sourceType = 'CHAT' AND requestId IS NULL AND requestHash IS NULL AND resultJson IS NULL)
        OR (sourceType = 'MANUAL' AND requestId IS NOT NULL AND requestHash IS NOT NULL AND resultJson IS NOT NULL)
    )
) COMMENT '长期记忆变更历史和手动操作去重记录' COLLATE = utf8mb4_unicode_ci;

    SELECT COUNT(*) INTO v_count FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='app_memory_event' AND index_name='uk_user_memory_request';
    IF v_count <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '手动请求去重索引不完整';
    END IF;
    SELECT '完成：记忆与旧证据保留，手动操作和聊天来源独立可追溯' AS result;
END$$
DELIMITER ;
CALL migrate_l2_memory_management();
DROP PROCEDURE IF EXISTS migrate_l2_memory_management;
