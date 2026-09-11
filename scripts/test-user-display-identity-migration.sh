#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
CONTAINER="${USER_IDENTITY_MYSQL_CONTAINER:-ai-codegen-e2e-mysql}"
TEST_DATABASE="user_identity_test_${RANDOM}_$$"
mkdir -p "$PROJECT_ROOT/.codex"

mysql_command() {
  docker exec -i "$CONTAINER" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot --batch --skip-column-names "$@"' sh "$@"
}

cleanup() {
  mysql_command -e "DROP DATABASE IF EXISTS $TEST_DATABASE" >/dev/null
}
trap cleanup EXIT

mysql_command -e "CREATE DATABASE $TEST_DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
mysql_command "$TEST_DATABASE" <<'SQL'
CREATE TABLE user (
  id BIGINT PRIMARY KEY, userAccount VARCHAR(256) NOT NULL,
  userPassword VARCHAR(512) NOT NULL, userName VARCHAR(256) NULL,
  userAvatar VARCHAR(1024) NULL, userProfile VARCHAR(512) NULL,
  userRole VARCHAR(256) NOT NULL DEFAULT 'user',
  editTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updateTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  isDelete TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_userAccount(userAccount), INDEX idx_userName(userName)
) COLLATE = utf8mb4_unicode_ci;
INSERT INTO user(id,userAccount,userPassword,userName,userAvatar,userProfile,userRole,isDelete) VALUES
  (1,'fixture-admin','test-hash','旧管理员','https://old.example/admin.jpg','保留管理员简介','admin',0),
  (2,'fixture-user','test-hash','无名',NULL,'保留用户简介','user',0),
  (3,'fixture-null','test-hash',NULL,'https://old.example/photo.jpg',NULL,'user',0),
  (4,'fixture-deleted','test-hash','无名','https://old.example/deleted.jpg',NULL,'user',1),
  (5,'fixture-valid','test-hash','用户_12345678','/api/default-user-avatar.jpg',NULL,'user',0);
CREATE TABLE identity_before AS SELECT * FROM user;
SQL

mysql_command "$TEST_DATABASE" < "$PROJECT_ROOT/sql/migrations/2026-09-10-user-display-identity.sql"
changes="$(mysql_command "$TEST_DATABASE" -e "SELECT COUNT(*) FROM user u JOIN identity_before b ON u.id=b.id WHERE
  NOT(u.userAccount<=>b.userAccount) OR NOT(u.userPassword<=>b.userPassword) OR
  NOT(u.userRole<=>b.userRole) OR NOT(u.userProfile<=>b.userProfile) OR
  NOT(u.editTime<=>b.editTime) OR NOT(u.createTime<=>b.createTime) OR
  NOT(u.updateTime<=>b.updateTime) OR NOT(u.isDelete<=>b.isDelete)")"
[[ "$changes" == 0 ]] || { echo '迁移错误修改了非展示字段'; exit 1; }
invalid="$(mysql_command "$TEST_DATABASE" -e "SELECT COUNT(*) FROM user WHERE NOT REGEXP_LIKE(userName,'^用户_[0-9]{8}$','c') OR userAvatar<>'/api/default-user-avatar.jpg'")"
[[ "$invalid" == 0 ]] || { echo '迁移后仍有不合法展示身份'; exit 1; }
mysql_command "$TEST_DATABASE" -e 'CREATE TABLE identity_after_first AS SELECT * FROM user'
mysql_command "$TEST_DATABASE" < "$PROJECT_ROOT/sql/migrations/2026-09-10-user-display-identity.sql"
changed_again="$(mysql_command "$TEST_DATABASE" -e 'SELECT COUNT(*) FROM user u JOIN identity_after_first b ON u.id=b.id WHERE NOT(u.userName<=>b.userName) OR NOT(u.userAvatar<=>b.userAvatar)')"
[[ "$changed_again" == 0 ]] || { echo '重复迁移改变了已经生成的昵称'; exit 1; }
if mysql_command "$TEST_DATABASE" -e "INSERT INTO user(id,userAccount,userPassword,userName) SELECT 6,'fixture-duplicate','test-hash',userName FROM user WHERE id=1" \
    >"$PROJECT_ROOT/.codex/user-display-duplicate-check.log" 2>&1; then
  echo '昵称唯一索引未阻止重复插入'
  exit 1
fi
echo '迁移验收通过：5 名用户（含管理员与已删除用户）、其他字段不变、重复执行稳定、唯一索引生效。'
