#!/bin/sh

set -eu

: "${REDIS_USERNAME:?REDIS_USERNAME不能为空}"
: "${INFRA_SHARED_PASSWORD:?INFRA_SHARED_PASSWORD不能为空}"

case "$REDIS_USERNAME" in
    *[!A-Za-z0-9._-]*)
        echo "REDIS_USERNAME只能包含字母、数字、点、下划线和短横线" >&2
        exit 64
        ;;
esac

case "$INFRA_SHARED_PASSWORD" in
    *[!A-Za-z0-9._-]*)
        echo "INFRA_SHARED_PASSWORD只能包含字母、数字、点、下划线和短横线" >&2
        exit 64
        ;;
esac

umask 077
acl_file=/tmp/users.acl
printf '%s\n' \
    'user default off' \
    "user ${REDIS_USERNAME} on >${INFRA_SHARED_PASSWORD} ~* +@all" \
    > "$acl_file"

exec redis-server \
    --appendonly yes \
    --aclfile "$acl_file"
