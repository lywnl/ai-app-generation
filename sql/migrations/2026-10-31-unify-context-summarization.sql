-- 统一 L1 摘要覆盖及用户回合决策证据。
-- 目标数据库：MySQL 8.0.40。执行前须停止生成写入、备份两张表并确认所选数据库。
-- ALTER TABLE 会隐式提交；本脚本只有条件 DDL，不进行历史正文或 L2 的 DML 回填。
-- 失败后检查已完成的列再重入，不宣称 ROLLBACK 可以撤销已经完成的 DDL。
-- 重复执行只验收元数据，保留已经写入的新覆盖与证据。

DROP PROCEDURE IF EXISTS migrate_unify_context_summarization;
DELIMITER $$
CREATE PROCEDURE migrate_unify_context_summarization()
BEGIN
    DECLARE v_column_count INT DEFAULT 0;
    IF DATABASE() IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '必须明确选择迁移数据库';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'app_memory_summary'
          AND column_name = 'summaryRevision'
    ) THEN
        ALTER TABLE app_memory_summary
            ADD COLUMN summaryRevision BIGINT NOT NULL DEFAULT 0
                COMMENT '摘要及覆盖快照的单调修订' AFTER lastSummarizedId;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'app_memory_summary'
          AND column_name = 'coverageVersion'
    ) THEN
        ALTER TABLE app_memory_summary
            ADD COLUMN coverageVersion INT NOT NULL DEFAULT 1
                COMMENT '1 旧历史游标，2 组合覆盖' AFTER summaryRevision;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'app_memory_summary'
          AND column_name = 'coverageState'
    ) THEN
        ALTER TABLE app_memory_summary
            ADD COLUMN coverageState MEDIUMTEXT NULL
                COMMENT '部分回合覆盖前沿 JSON' AFTER coverageVersion;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'chat_history'
          AND column_name = 'decisionEvidence'
    ) THEN
        ALTER TABLE chat_history
            ADD COLUMN decisionEvidence MEDIUMTEXT NULL
                COMMENT '用户回合独立决策证据 JSON' AFTER memoryOutcome;
    END IF;

    SELECT COUNT(*) INTO v_column_count FROM information_schema.columns
    WHERE table_schema = DATABASE() AND (
        (table_name = 'app_memory_summary' AND column_name = 'summaryRevision'
            AND column_type = 'bigint' AND is_nullable = 'NO' AND column_default = '0')
        OR (table_name = 'app_memory_summary' AND column_name = 'coverageVersion'
            AND column_type = 'int' AND is_nullable = 'NO' AND column_default = '1')
        OR (table_name = 'app_memory_summary' AND column_name = 'coverageState'
            AND data_type = 'mediumtext' AND is_nullable = 'YES' AND column_default IS NULL)
        OR (table_name = 'chat_history' AND column_name = 'decisionEvidence'
            AND data_type = 'mediumtext' AND is_nullable = 'YES' AND column_default IS NULL)
    );
    IF v_column_count <> 4 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '统一摘要新增列元数据验收失败，禁止启动新写入器';
    END IF;

    SELECT table_name, column_name, column_type, is_nullable, column_default
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND ((table_name = 'app_memory_summary'
            AND column_name IN ('summaryRevision', 'coverageVersion', 'coverageState'))
           OR (table_name = 'chat_history' AND column_name = 'decisionEvidence'))
    ORDER BY table_name, ordinal_position;
END$$
DELIMITER ;
CALL migrate_unify_context_summarization();
DROP PROCEDURE IF EXISTS migrate_unify_context_summarization;

-- 回退：仅加列且尚未写新格式时可保留列并回退程序。
-- 已写入新覆盖或 L0 封装后只允许兼容这些格式的修复版本接管。
-- 不删除新摘要、证据或列来伪装旧格式兼容；新旧写入器不得混用。
