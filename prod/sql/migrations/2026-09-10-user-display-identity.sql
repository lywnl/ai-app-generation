-- 在目标数据库执行；迁移期间暂停用户写入，完成后部署对应代码并清理精选分页缓存。
-- 备份表仅保存展示字段与新名称映射，不保存账号、密码或其他用户资料。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS user_display_identity_backup_20260910 (
    userId BIGINT NOT NULL PRIMARY KEY,
    oldUserName VARCHAR(256) NULL,
    oldUserAvatar VARCHAR(1024) NULL,
    newUserName VARCHAR(32) NULL,
    migratedAt DATETIME NULL,
    UNIQUE KEY uk_newUserName (newUserName)
) COLLATE = utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS migrate_user_display_identity_20260910;
DELIMITER $$
CREATE PROCEDURE migrate_user_display_identity_20260910()
BEGIN
    DECLARE finished BOOLEAN DEFAULT FALSE;
    DECLARE targetId BIGINT;
    DECLARE candidate VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    DECLARE attempts INT;
    DECLARE occupied BIGINT;
    DECLARE pending CURSOR FOR
        SELECT b.userId FROM user_display_identity_backup_20260910 b
        INNER JOIN `user` u ON u.id = b.userId
        WHERE b.migratedAt IS NULL ORDER BY b.userId;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished = TRUE;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;
    INSERT IGNORE INTO user_display_identity_backup_20260910 (userId, oldUserName, oldUserAvatar)
        SELECT id, userName, userAvatar FROM `user`
        WHERE userName IS NULL OR NOT REGEXP_LIKE(userName, '^用户_[0-9]{8}$', 'c')
            OR userAvatar IS NULL OR userAvatar <> '/api/default-user-avatar.jpg'
            OR userName IN (SELECT duplicateName FROM (
                SELECT userName AS duplicateName FROM `user`
                GROUP BY userName HAVING COUNT(*) > 1
            ) duplicates);

    OPEN pending;
    migration_loop: LOOP
        FETCH pending INTO targetId;
        IF finished THEN LEAVE migration_loop; END IF;
        SET attempts = 0;
        name_loop: LOOP
            -- RANDOM_BYTES 不依赖用户 ID，前导零也属于八位数字。
            SET candidate = CONCAT('用户_', LPAD(MOD(
                CAST(CONV(HEX(RANDOM_BYTES(4)), 16, 10) AS UNSIGNED), 100000000), 8, '0'));
            SET attempts = attempts + 1;
            SELECT COUNT(*) INTO occupied FROM `user` WHERE userName = candidate;
            IF occupied = 0 THEN
                SELECT COUNT(*) INTO occupied FROM user_display_identity_backup_20260910
                WHERE newUserName = candidate;
            END IF;
            IF occupied = 0 THEN LEAVE name_loop; END IF;
            IF attempts >= 100 THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '用户展示名称生成冲突，请检查后重试';
            END IF;
        END LOOP;
        UPDATE user_display_identity_backup_20260910
        SET newUserName = candidate, migratedAt = CURRENT_TIMESTAMP WHERE userId = targetId;
        UPDATE `user` SET userName = candidate, userAvatar = '/api/default-user-avatar.jpg',
            updateTime = updateTime WHERE id = targetId;
    END LOOP;
    CLOSE pending;
    COMMIT;

    -- MySQL DDL 隐式提交，放在展示字段事务完成之后；重跑不会重新生成已迁移昵称。
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'user' AND index_name = 'uk_userName') THEN
        ALTER TABLE `user` ADD UNIQUE KEY uk_userName (userName);
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'user' AND index_name = 'idx_userName') THEN
        ALTER TABLE `user` DROP INDEX idx_userName;
    END IF;
    ALTER TABLE `user`
        MODIFY userName VARCHAR(256) NOT NULL COMMENT '持久化展示昵称：用户_加8位数字',
        MODIFY userAvatar VARCHAR(1024) NOT NULL DEFAULT '/api/default-user-avatar.jpg'
            COMMENT '统一用户头像';
END$$
DELIMITER ;

CALL migrate_user_display_identity_20260910();
DROP PROCEDURE migrate_user_display_identity_20260910;

SELECT COUNT(*) AS totalUsers,
    COUNT(DISTINCT userName) AS uniqueNames,
    SUM(NOT REGEXP_LIKE(userName, '^用户_[0-9]{8}$', 'c')) AS invalidNames,
    SUM(userAvatar <> '/api/default-user-avatar.jpg') AS invalidAvatars
FROM `user`;
