-- 聊天展示文本与模型记忆投影分离
-- 目标数据库：MySQL 8.0.40。
-- ALTER TABLE 会隐式提交，因此先条件加列，再单独事务回填，最后验收元数据。
-- 脚本不修改 chat_history.message，可安全重复执行。

DROP PROCEDURE IF EXISTS migrate_chat_history_memory_projection;
DELIMITER $$

CREATE PROCEDURE migrate_chat_history_memory_projection()
BEGIN
    DECLARE v_column_count INT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'chat_history'
          AND column_name = 'memoryMessage'
    ) THEN
        ALTER TABLE chat_history
            ADD COLUMN memoryMessage MEDIUMTEXT NULL
                COMMENT '模型记忆投影'
                AFTER message;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'chat_history'
          AND column_name = 'memoryOutcome'
    ) THEN
        ALTER TABLE chat_history
            ADD COLUMN memoryOutcome VARCHAR(32) NULL
                COMMENT '记忆投影结果'
                AFTER memoryMessage;
    END IF;

    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_history'
      AND column_name = 'memoryMessage'
      AND data_type = 'mediumtext'
      AND is_nullable = 'YES';
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'chat_history.memoryMessage 加列验收失败';
    END IF;

    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_history'
      AND column_name = 'memoryOutcome'
      AND data_type = 'varchar'
      AND character_maximum_length = 32
      AND is_nullable = 'YES';
    IF v_column_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'chat_history.memoryOutcome 加列验收失败';
    END IF;

    START TRANSACTION;

    UPDATE chat_history
    SET memoryMessage = NULL,
        memoryOutcome = NULL
    WHERE messageType = 'user'
      AND (memoryMessage IS NOT NULL OR memoryOutcome IS NOT NULL);

    UPDATE chat_history
    SET memoryMessage =
            '本轮发生工具协议异常，未完成真实工具执行或构建。不得复用本轮生成内容，后续操作以当前工程文件为准。',
        memoryOutcome = 'PROTOCOL_ERROR'
    WHERE id = 447109043745288192
      AND messageType = 'ai'
      AND memoryMessage IS NULL
      AND memoryOutcome IS NULL;

    UPDATE chat_history AS h
    JOIN app AS a ON a.id = h.appId
    SET h.memoryMessage =
            '历史 Vue 回合缺少可信结构化执行证据。本轮内容不得作为后续工程状态依据，后续操作以当前工程文件为准。',
        h.memoryOutcome = 'LEGACY_UNVERIFIED'
    WHERE h.messageType = 'ai'
      AND a.codeGenType = 'vue_project'
      AND h.id <> 447109043745288192
      AND h.memoryMessage IS NULL
      AND h.memoryOutcome IS NULL;

    UPDATE chat_history AS h
    JOIN app AS a ON a.id = h.appId
    SET h.memoryMessage = h.message,
        h.memoryOutcome = 'LEGACY_IMPORTED'
    WHERE h.messageType = 'ai'
      AND a.codeGenType IN ('html', 'multi_file')
      AND h.id <> 447109043745288192
      AND h.memoryMessage IS NULL
      AND h.memoryOutcome IS NULL;

    COMMIT;
END$$

DELIMITER ;

CALL migrate_chat_history_memory_projection();

SELECT table_name,
       column_name,
       column_type,
       is_nullable,
       column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'chat_history'
  AND column_name IN ('memoryMessage', 'memoryOutcome')
ORDER BY ordinal_position;

SELECT messageType,
       memoryOutcome,
       COUNT(*) AS rowCount
FROM chat_history
GROUP BY messageType, memoryOutcome
ORDER BY messageType, memoryOutcome;

DROP PROCEDURE IF EXISTS migrate_chat_history_memory_projection;
