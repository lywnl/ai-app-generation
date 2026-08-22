-- Token 分层记忆 V3 数据结构升级
-- 目标数据库：MySQL 8.0.40。
-- 注意：ALTER TABLE 会隐式提交，多表 DDL 不能由普通事务整体回滚。
-- 本脚本按“条件加列 -> 独立事务回填 -> 条件收紧 -> 验收”分阶段执行，
-- 任一步失败后都可以重新执行；脚本开头和结尾都会清理临时存储过程。

-- 执行前：确认三张表数据量。
SELECT 'app_memory_summary' AS tableName, COUNT(*) AS rowCount
FROM app_memory_summary
UNION ALL
SELECT 'app_memory_extract_cursor' AS tableName, COUNT(*) AS rowCount
FROM app_memory_extract_cursor
UNION ALL
SELECT 'app_memory' AS tableName, COUNT(*) AS rowCount
FROM app_memory;

-- 执行前：先由运维确认空间并备份 app_memory，备份表名可按实际批次调整。
-- CREATE TABLE app_memory_backup_20260815 LIKE app_memory;
-- INSERT INTO app_memory_backup_20260815 SELECT * FROM app_memory;
-- 若备份表已经存在，禁止覆盖；先核对备份行数和主键范围。

DROP PROCEDURE IF EXISTS migrate_token_layered_memory_v3;
DELIMITER $$

CREATE PROCEDURE migrate_token_layered_memory_v3()
BEGIN
    DECLARE v_column_count INT DEFAULT 0;
    DECLARE v_null_rows BIGINT DEFAULT 0;
    DECLARE v_backfilled_rows BIGINT DEFAULT 0;

    -- 阶段一：仅在列不存在时增加可回填列，并立即通过元数据验收。
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory_summary'
          AND column_name = 'nextRetryTime'
    ) THEN
        ALTER TABLE app_memory_summary
            ADD COLUMN nextRetryTime DATETIME NULL DEFAULT NULL
                COMMENT '下一次允许后台重试时间'
                AFTER failCount;
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory_summary'
      AND column_name = 'nextRetryTime';
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory_summary.nextRetryTime 加列验收失败';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory_extract_cursor'
          AND column_name = 'nextRetryTime'
    ) THEN
        ALTER TABLE app_memory_extract_cursor
            ADD COLUMN nextRetryTime DATETIME NULL DEFAULT NULL
                COMMENT '下一次允许后台重试时间'
                AFTER failCount;
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory_extract_cursor'
      AND column_name = 'nextRetryTime';
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory_extract_cursor.nextRetryTime 加列验收失败';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory'
          AND column_name = 'status'
    ) THEN
        ALTER TABLE app_memory
            ADD COLUMN status VARCHAR(16) NULL DEFAULT 'ACTIVE'
                COMMENT '证据状态:CANDIDATE/ACTIVE'
                AFTER content;
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory'
      AND column_name = 'status';
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory.status 加列验收失败';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory'
          AND column_name = 'evidenceType'
    ) THEN
        ALTER TABLE app_memory
            ADD COLUMN evidenceType VARCHAR(16) NULL DEFAULT 'EXPLICIT'
                COMMENT '证据类型:EXPLICIT/IMPLICIT'
                AFTER status;
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory'
      AND column_name = 'evidenceType';
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory.evidenceType 加列验收失败';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory'
          AND column_name = 'evidenceCount'
    ) THEN
        ALTER TABLE app_memory
            ADD COLUMN evidenceCount INT NULL DEFAULT 1
                COMMENT '不同完整回合证据数'
                AFTER evidenceType;
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory'
      AND column_name = 'evidenceCount';
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory.evidenceCount 加列验收失败';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory'
          AND column_name = 'lastEvidenceTurnId'
    ) THEN
        ALTER TABLE app_memory
            ADD COLUMN lastEvidenceTurnId BIGINT NULL DEFAULT NULL
                COMMENT '已累计证据中的最大User回合ID'
                AFTER evidenceCount;
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory'
      AND column_name = 'lastEvidenceTurnId';
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory.lastEvidenceTurnId 加列验收失败';
    END IF;

    -- 阶段二：旧偏好数据只在这个显式事务内回填；DDL 不属于该事务。
    START TRANSACTION;
    UPDATE app_memory
    SET status = COALESCE(status, 'ACTIVE'),
        evidenceType = COALESCE(evidenceType, 'EXPLICIT'),
        evidenceCount = COALESCE(evidenceCount, 1)
    WHERE status IS NULL
       OR evidenceType IS NULL
       OR evidenceCount IS NULL;
    SET v_backfilled_rows = ROW_COUNT();
    COMMIT;
    SELECT v_backfilled_rows AS appMemoryBackfilledRows;

    SELECT COUNT(*) INTO v_null_rows
    FROM app_memory
    WHERE status IS NULL
       OR evidenceType IS NULL
       OR evidenceCount IS NULL;
    IF v_null_rows <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory 兼容列仍存在 NULL，停止收紧约束';
    END IF;

    -- 阶段三：按元数据条件收紧最终列定义，保留旧应用写入兼容默认值。
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory_summary'
          AND column_name = 'nextRetryTime'
          AND (data_type <> 'datetime'
               OR is_nullable <> 'YES'
               OR column_default IS NOT NULL)
    ) THEN
        ALTER TABLE app_memory_summary
            MODIFY COLUMN nextRetryTime DATETIME NULL DEFAULT NULL
                COMMENT '下一次允许后台重试时间';
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory_summary'
      AND column_name = 'nextRetryTime'
      AND data_type = 'datetime'
      AND is_nullable = 'YES'
      AND column_default IS NULL;
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory_summary.nextRetryTime 最终定义验收失败';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory_extract_cursor'
          AND column_name = 'nextRetryTime'
          AND (data_type <> 'datetime'
               OR is_nullable <> 'YES'
               OR column_default IS NOT NULL)
    ) THEN
        ALTER TABLE app_memory_extract_cursor
            MODIFY COLUMN nextRetryTime DATETIME NULL DEFAULT NULL
                COMMENT '下一次允许后台重试时间';
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory_extract_cursor'
      AND column_name = 'nextRetryTime'
      AND data_type = 'datetime'
      AND is_nullable = 'YES'
      AND column_default IS NULL;
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory_extract_cursor.nextRetryTime 最终定义验收失败';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory'
          AND column_name = 'status'
          AND (data_type <> 'varchar'
               OR character_maximum_length <> 16
               OR is_nullable <> 'NO'
               OR COALESCE(column_default, '') <> 'ACTIVE')
    ) THEN
        ALTER TABLE app_memory
            MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                COMMENT '证据状态:CANDIDATE/ACTIVE';
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory'
      AND column_name = 'status'
      AND data_type = 'varchar'
      AND character_maximum_length = 16
      AND is_nullable = 'NO'
      AND column_default = 'ACTIVE';
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory.status 最终定义验收失败';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory'
          AND column_name = 'evidenceType'
          AND (data_type <> 'varchar'
               OR character_maximum_length <> 16
               OR is_nullable <> 'NO'
               OR COALESCE(column_default, '') <> 'EXPLICIT')
    ) THEN
        ALTER TABLE app_memory
            MODIFY COLUMN evidenceType VARCHAR(16) NOT NULL DEFAULT 'EXPLICIT'
                COMMENT '证据类型:EXPLICIT/IMPLICIT';
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory'
      AND column_name = 'evidenceType'
      AND data_type = 'varchar'
      AND character_maximum_length = 16
      AND is_nullable = 'NO'
      AND column_default = 'EXPLICIT';
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory.evidenceType 最终定义验收失败';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory'
          AND column_name = 'evidenceCount'
          AND (data_type <> 'int'
               OR is_nullable <> 'NO'
               OR COALESCE(column_default, '') <> '1')
    ) THEN
        ALTER TABLE app_memory
            MODIFY COLUMN evidenceCount INT NOT NULL DEFAULT 1
                COMMENT '不同完整回合证据数';
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory'
      AND column_name = 'evidenceCount'
      AND data_type = 'int'
      AND is_nullable = 'NO'
      AND column_default = '1';
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory.evidenceCount 最终定义验收失败';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_memory'
          AND column_name = 'lastEvidenceTurnId'
          AND (data_type <> 'bigint'
               OR is_nullable <> 'YES'
               OR column_default IS NOT NULL)
    ) THEN
        ALTER TABLE app_memory
            MODIFY COLUMN lastEvidenceTurnId BIGINT NULL DEFAULT NULL
                COMMENT '已累计证据中的最大User回合ID';
    END IF;
    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app_memory'
      AND column_name = 'lastEvidenceTurnId'
      AND data_type = 'bigint'
      AND is_nullable = 'YES'
      AND column_default IS NULL;
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'app_memory.lastEvidenceTurnId 最终定义验收失败';
    END IF;
END$$

DELIMITER ;

CALL migrate_token_layered_memory_v3();

-- 执行后：验收最终列类型、NULL 属性和默认值。
SELECT table_name,
       column_name,
       column_type,
       is_nullable,
       column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND (
      (table_name = 'app_memory_summary'
          AND column_name = 'nextRetryTime')
      OR (table_name = 'app_memory_extract_cursor'
          AND column_name = 'nextRetryTime')
      OR (table_name = 'app_memory'
          AND column_name IN (
              'status', 'evidenceType', 'evidenceCount',
              'lastEvidenceTurnId'))
  )
ORDER BY table_name, ordinal_position;

-- 执行后：兼容列必须没有 NULL；旧数据迁移为 ACTIVE + EXPLICIT + 1，
-- lastEvidenceTurnId 未参与回填并保持 NULL。
SELECT COUNT(*) AS appMemoryNullCompatibilityRows
FROM app_memory
WHERE status IS NULL
   OR evidenceType IS NULL
   OR evidenceCount IS NULL;

SELECT COUNT(*) AS appMemoryCompatibleRows
FROM app_memory
WHERE status = 'ACTIVE'
  AND evidenceType = 'EXPLICIT'
  AND evidenceCount = 1;

-- 执行后：验收既有唯一索引仍然存在。
SELECT table_name,
       index_name,
       non_unique,
       seq_in_index,
       column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND (
      (table_name = 'app_memory_summary' AND index_name = 'uk_appId')
      OR (table_name = 'app_memory_extract_cursor'
          AND index_name = 'uk_appId')
      OR (table_name = 'app_memory'
          AND index_name = 'uk_userId_type_name')
  )
ORDER BY table_name, index_name, seq_in_index;

-- 默认回滚：仅回滚应用版本并保留这些兼容新列，旧版本不写新列仍可插入。
--
-- 破坏性手工回滚参考（禁止自动执行）：先导出新增列内容并确认无新版本流量。
-- ALTER TABLE app_memory_summary DROP COLUMN nextRetryTime;
-- ALTER TABLE app_memory_extract_cursor DROP COLUMN nextRetryTime;
-- ALTER TABLE app_memory DROP COLUMN lastEvidenceTurnId;
-- ALTER TABLE app_memory DROP COLUMN evidenceCount;
-- ALTER TABLE app_memory DROP COLUMN evidenceType;
-- ALTER TABLE app_memory DROP COLUMN status;

DROP PROCEDURE IF EXISTS migrate_token_layered_memory_v3;
