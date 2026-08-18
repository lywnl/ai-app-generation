#!/usr/bin/env bash
set -euo pipefail

since='2026-08-18T11:00:00+08:00'
until_epoch="$(date '+%s')"

echo 'evidence=task-9-container-and-volume-audit'
echo "captured_at=$(date '+%Y-%m-%dT%H:%M:%S%z')"
echo "docker_context=$(docker context show)"
docker info --format 'daemon_id={{.ID}} daemon_name={{.Name}} server_version={{.ServerVersion}}'
echo "scope=${since}..epoch:${until_epoch}"
echo 'container_event_filters=type=container,event=create,destroy,start,stop,die,restart,kill,oom,pause,unpause'
echo 'volume_event_filters=type=volume,event=create,destroy,prune'
echo 'exec_event_filters=type=container,event=exec_create'
echo 'event_history_boundary=仅审计当前daemon仍可返回的历史事件窗口；StartedAt前后文件是目标容器连续性的主证据'
echo 'exec_visibility_boundary=exec_create命令文本不覆盖stdin、重定向文件或被调用脚本内容；危险命中仅作补强，不作为无清理的单独证明'
echo 'dangerous_exec_regex=数据库/Redis全量清理、记忆表DDL或DELETE、数据目录rm/find-delete/truncate/dd/重定向'

lifecycle_events="$(docker events --since "$since" --until "$until_epoch" \
  --filter type=container \
  --filter event=create --filter event=destroy --filter event=start \
  --filter event=stop --filter event=die --filter event=restart \
  --filter event=kill --filter event=oom --filter event=pause \
  --filter event=unpause \
  --format '{{.Time}}\t{{.Type}}\t{{.Action}}\t{{.Actor.Attributes.name}}')"
volume_events="$(docker events --since "$since" --until "$until_epoch" \
  --filter type=volume --filter event=create --filter event=destroy \
  --filter event=prune \
  --format '{{.Time}}\t{{.Type}}\t{{.Action}}\t{{.Actor.ID}}')"
exec_events="$(docker events --since "$since" --until "$until_epoch" \
  --filter type=container --filter event=exec_create --format '{{.Action}}')"

dangerous_pattern='(flushall|flushdb|(^|[[:space:];|&])(del|unlink)[[:space:]]|truncate([[:space:]]+table)?[[:space:]]+(`?[^`.[:space:]]+`?\.)?`?app_memory|delete[[:space:]]+from[[:space:]]+(`?[^`.[:space:]]+`?\.)?`?app_memory|drop[[:space:]]+(table[[:space:]]+(`?[^`.[:space:]]+`?\.)?`?app_memory|database[[:space:]]+`?ai_app_generation)|rm[[:space:]].*/var/lib/(mysql|redis|postgresql)|find[[:space:]].*/var/lib/(mysql|redis|postgresql).*-delete|(^|[[:space:];|&])truncate[[:space:]].*/var/lib/(mysql|redis|postgresql)|dd[[:space:]].*of=.*/var/lib/(mysql|redis|postgresql)|(^|[[:space:];|&])>[[:space:]]*/var/lib/(mysql|redis|postgresql))'
dangerous_count="$(printf '%s\n' "$exec_events" | grep -Eic "$dangerous_pattern" || true)"

echo "container_lifecycle_event_count=$(printf '%s\n' "$lifecycle_events" | awk 'NF {count++} END {print count+0}')"
echo 'container_lifecycle_events_begin'
printf '%s\n' "$lifecycle_events"
echo 'container_lifecycle_events_end'
echo "volume_destructive_event_count=$(printf '%s\n' "$volume_events" | awk 'NF {count++} END {print count+0}')"
echo 'volume_destructive_events_begin'
printf '%s\n' "$volume_events"
echo 'volume_destructive_events_end'
echo "exec_create_count=$(printf '%s\n' "$exec_events" | awk 'NF {count++} END {print count+0}')"
echo "dangerous_exec_create_count=$dangerous_count"
echo 'exec_commands_redacted=true'

# Redis INFO commandstats 是当前 Redis 进程启动以来的累计计数，能够覆盖通过
# stdin 或脚本发出的 FLUSH 命令；密码只从应用配置读取且不写入证据。
redis_password="$(awk '
  /^[[:space:]]+redis:/ { in_redis = 1; next }
  in_redis && /^[[:space:]]+password:/ {
    sub(/^[^:]*:[[:space:]]*/, "")
    gsub(/"/, "")
    print
    exit
  }
' src/main/resources/application.yml)"
if [[ -z "$redis_password" ]]; then
  echo 'redis_commandstats_available=false'
  exit 1
fi
redis_exec() {
  local section="$1"
  # 密码只经 stdin 传入，不进入 docker exec 的 argv 或事件 Action 文本。
  docker exec -i ai-codegen-rag-eval-redis sh -c '
    IFS= read -r REDISCLI_AUTH
    export REDISCLI_AUTH
    exec redis-cli --no-auth-warning INFO "$1"
  ' sh "$section" <<< "$redis_password"
}
redis_info="$(redis_exec server 2>/dev/null | tr -d '\r')"
redis_commandstats="$(redis_exec commandstats 2>/dev/null | tr -d '\r')"
echo 'redis_commandstats_available=true'
printf '%s\n' "$redis_info" | awk -F: '
  $1 == "redis_version" { print "redis_version=" $2 }
  $1 == "process_id" { print "redis_process_id=" $2 }
  $1 == "uptime_in_seconds" { print "redis_uptime_in_seconds=" $2 }
'
for command in flushall flushdb; do
  calls="$(printf '%s\n' "$redis_commandstats" \
    | awk -F'[:,=]' -v key="cmdstat_${command}" \
        '$1 == key { for (i = 2; i <= NF; i++) if ($(i) == "calls") print $(i + 1) }')"
  calls="${calls:-0}"
  echo "redis_${command}_calls_since_process_start=$calls"
  if (( calls != 0 )); then
    echo 'redis_flush_commands_absent=false'
    exit 1
  fi
done
echo 'redis_flush_commands_absent=true'

echo 'all_containers_begin'
docker ps -a --format '{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.CreatedAt}}' | sort
echo 'all_containers_end'
container_count="$(docker ps -a --format '{{.Names}}' | awk 'NF { count++ } END { print count + 0 }')"
echo "current_container_count=$container_count"
if (( container_count != 4 )); then
  echo 'current_container_set_exactly_four=false'
  exit 1
fi
echo 'current_container_set_exactly_four=true'
echo 'target_started_at_begin'
docker inspect -f '{{.Name}}\t{{.State.StartedAt}}\t{{.State.Status}}' \
  ai-app-generation-dev-nginx \
  ai-codegen-e2e-mysql \
  ai-codegen-rag-eval-redis \
  ai-codegen-rag-eval-pg
echo 'target_started_at_end'
before_file='.codex/verification/task-9-containers-started-at-before-final.txt'
after_file='.codex/verification/task-9-containers-started-at-after-final.txt'
if [[ ! -f "$before_file" || ! -f "$after_file" ]]; then
  echo 'started_at_checkpoint_files_present=false'
  exit 1
fi
echo 'started_at_checkpoint_files_present=true'
if cmp -s "$before_file" "$after_file"; then
  echo 'started_at_before_after_equal=true'
else
  echo 'started_at_before_after_equal=false'
  exit 1
fi
echo 'audit_exit=0'
