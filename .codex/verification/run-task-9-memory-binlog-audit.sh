#!/usr/bin/env bash
set -euo pipefail

checkpoint_binlog='binlog.000005'
checkpoint=915514
container='ai-codegen-e2e-mysql'
host_mysqlbinlog='.codex/runtime/mysql-client-rpm/extracted/usr/bin/mysqlbinlog'
container_mysqlbinlog='/tmp/task9-mysqlbinlog'

mysql_exec() {
  docker exec -i "$container" sh -lc \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw' <<< "$1"
}

echo 'evidence=task-9-memory-binlog-audit'
echo "captured_at=$(date '+%Y-%m-%dT%H:%M:%S%z')"
echo "source_container=$container"
echo "checkpoint_binlog=$checkpoint_binlog"
echo "checkpoint_position=$checkpoint"
echo 'checkpoint_reason=任务9不可覆盖备份表的CREATE TABLE事件'
echo 'query=跨全部后续binlog执行SHOW BINLOG EVENTS；统计检查点后的三张记忆表行事件与破坏性Query'
echo 'row_decode=使用MySQL 8.0.40 mysqlbinlog --base64-output=decode-rows -vv 检查经数据库观测确认的FULL行镜像中的isDelete变化'

binlog_row_image="$(mysql_exec 'SELECT @@GLOBAL.binlog_row_image;' | awk 'NR == 2')"
echo "binlog_row_image=$binlog_row_image"
if [[ "$binlog_row_image" != 'FULL' ]]; then
  echo 'binlog_row_image_full=false'
  exit 1
fi
echo 'binlog_row_image_full=true'
last_column_check="$(mysql_exec "
  SELECT c.table_name, c.column_name
  FROM information_schema.columns c
  JOIN (
    SELECT table_name, MAX(ordinal_position) AS max_ordinal
    FROM information_schema.columns
    WHERE table_schema = 'ai_app_generation'
      AND table_name IN (
        'app_memory', 'app_memory_summary', 'app_memory_extract_cursor'
      )
    GROUP BY table_name
  ) last_column
    ON last_column.table_name = c.table_name
   AND last_column.max_ordinal = c.ordinal_position
  WHERE c.table_schema = 'ai_app_generation'
  ORDER BY c.table_name;
")"
tracked_last_column_count="$(printf '%s\n' "$last_column_check" \
  | awk 'NR > 1 && NF { count++ } END { print count + 0 }')"
is_delete_last_column_count="$(printf '%s\n' "$last_column_check" \
  | awk 'NR > 1 && $2 == "isDelete" { count++ } END { print count + 0 }')"
echo "tracked_last_column_count=$tracked_last_column_count"
echo "is_delete_last_column_count=$is_delete_last_column_count"
if (( tracked_last_column_count != 3 || is_delete_last_column_count != 3 )); then
  echo 'tracked_tables_end_with_is_delete=false'
  exit 1
fi
echo 'tracked_tables_end_with_is_delete=true'

checkpoint_event="$(mysql_exec "SHOW BINLOG EVENTS IN '$checkpoint_binlog' FROM $checkpoint LIMIT 1;")"
if ! printf '%s\n' "$checkpoint_event" | grep -q \
  'CREATE TABLE chat_history_memory_projection_backup_20260818_223033'; then
  echo 'checkpoint_verified=false'
  exit 1
fi
echo 'checkpoint_verified=true'

events_file="$(mktemp)"
history_file="$(mktemp)"
decoded_history_file="$(mktemp)"
cleanup() {
  rm -f "$events_file" "$history_file" "$decoded_history_file"
  docker exec "$container" rm -f "$container_mysqlbinlog" >/dev/null 2>&1 || true
}
trap cleanup EXIT
checkpoint_seen=false
inspected_binlogs=()
all_binlogs=()

if [[ ! -x "$host_mysqlbinlog" ]]; then
  echo 'mysqlbinlog_host_binary_available=false'
  exit 1
fi
docker cp "$host_mysqlbinlog" "$container:$container_mysqlbinlog" >/dev/null
docker exec "$container" chmod 755 "$container_mysqlbinlog"
echo 'mysqlbinlog_host_binary_available=true'

# 先冻结审计终点，再严格按该 file/position 读取，消除“先读事件、后取终点”的竞态。
read -r endpoint_binlog endpoint_position < <(
  mysql_exec 'SHOW MASTER STATUS;' | awk 'NR == 2 { print $1, $2 }'
)
if [[ -z "$endpoint_binlog" || ! "$endpoint_position" =~ ^[0-9]+$ ]]; then
  echo 'audit_endpoint_valid=false'
  exit 1
fi
echo 'audit_endpoint_valid=true'
echo "audit_endpoint_binlog=$endpoint_binlog"
echo "audit_endpoint_position=$endpoint_position"

endpoint_seen=false
while IFS= read -r current_binlog; do
  all_binlogs+=("$current_binlog")
  to_position=''
  if [[ "$current_binlog" == "$endpoint_binlog" ]]; then
    to_position=$endpoint_position
    endpoint_seen=true
  fi
  show_limit=''
  if [[ -n "$to_position" ]]; then
    show_limit="LIMIT 0, 18446744073709551615"
  fi
  mysql_exec "SHOW BINLOG EVENTS IN '$current_binlog' FROM 4 $show_limit;" \
    | awk -F '\t' -v endpoint="$to_position" \
        'endpoint == "" || NR == 1 || ($2 + 0) < endpoint' >> "$history_file"

  if ! docker exec "$container" test -x "$container_mysqlbinlog"; then
    echo 'mysqlbinlog_available=false'
    exit 1
  fi
  decode_args=(--base64-output=decode-rows -vv --start-position=4)
  if [[ -n "$to_position" ]]; then
    decode_args+=(--stop-position="$to_position")
  fi
  docker exec "$container" "$container_mysqlbinlog" "${decode_args[@]}" \
    "/var/lib/mysql/$current_binlog" >> "$decoded_history_file"

  if [[ "$current_binlog" == "$checkpoint_binlog" ]]; then
    checkpoint_seen=true
  fi
  if [[ "$checkpoint_seen" != true ]]; then
    continue
  fi
  from_position=4
  if [[ "$current_binlog" == "$checkpoint_binlog" ]]; then
    from_position=$checkpoint
  fi
  mysql_exec "SHOW BINLOG EVENTS IN '$current_binlog' FROM $from_position $show_limit;" \
    | awk -F '\t' -v endpoint="$to_position" \
        'endpoint == "" || NR == 1 || ($2 + 0) < endpoint' >> "$events_file"
  inspected_binlogs+=("$current_binlog")
  if [[ "$current_binlog" == "$endpoint_binlog" ]]; then
    break
  fi
done < <(mysql_exec 'SHOW BINARY LOGS;' | awk 'NR > 1 {print $1}')
if [[ "$checkpoint_seen" != true ]]; then
  echo 'checkpoint_binlog_present=false'
  exit 1
fi
if [[ "$endpoint_seen" != true ]]; then
  echo 'audit_endpoint_seen=false'
  exit 1
fi
echo 'audit_endpoint_seen=true'
last_history_position="$(awk -F '\t' 'NR > 1 { position = $2 + 0 } END { print position + 0 }' "$history_file")"
if (( last_history_position >= endpoint_position )); then
  echo 'audit_endpoint_exclusive_boundary_valid=false'
  exit 1
fi
echo 'audit_endpoint_exclusive_boundary_valid=true'
echo "last_show_binlog_event_position=$last_history_position"
echo 'checkpoint_binlog_present=true'
echo "inspected_binlogs=$(IFS=,; echo "${inspected_binlogs[*]}")"
echo "retained_history_binlogs=$(IFS=,; echo "${all_binlogs[*]}")"
if [[ "${all_binlogs[0]:-}" != 'binlog.000001' ]]; then
  echo 'retained_history_starts_at_first_binlog=false'
  exit 1
fi
echo 'retained_history_starts_at_first_binlog=true'
echo 'retained_history_boundary=当前daemon仍保留的binlog.000001至冻结终点；不外推到未保留日志或其他daemon'

awk -F '\t' '
  BEGIN {
    tracked["app_memory"] = 1
    tracked["app_memory_summary"] = 1
    tracked["app_memory_extract_cursor"] = 1
  }
  $3 == "Table_map" {
    text = $6
    id = text
    sub(/^table_id: /, "", id)
    sub(/ .*/, "", id)
    name = text
    sub(/^.*ai_app_generation\./, "", name)
    sub(/\).*$/, "", name)
    table[id] = name
    next
  }
  $3 ~ /^(Write_rows|Update_rows|Delete_rows)$/ {
    text = $6
    id = text
    sub(/^table_id: /, "", id)
    sub(/ .*/, "", id)
    name = table[id]
    if (tracked[name]) {
      count[name, $3]++
    }
    next
  }
  $3 == "Query" {
    query = tolower($6)
    gsub(/`/, "", query)
    gsub(/[[:space:]]+/, " ", query)
    table_pattern = "(ai_app_generation\\.)?app_memory(_summary|_extract_cursor)?"
    destructive = "(truncate( table)?|drop table|delete from) " table_pattern "([ ;]|$)"
    if (query ~ destructive || query ~ /drop database ai_app_generation([ ;]|$)/) {
      destructive_queries++
    }
  }
  END {
    names[1] = "app_memory"
    names[2] = "app_memory_summary"
    names[3] = "app_memory_extract_cursor"
    for (i = 1; i <= 3; i++) {
      name = names[i]
      printf "%s_write_rows=%d\n", name, count[name, "Write_rows"] + 0
      printf "%s_update_rows=%d\n", name, count[name, "Update_rows"] + 0
      printf "%s_delete_rows=%d\n", name, count[name, "Delete_rows"] + 0
    }
    printf "destructive_query_count=%d\n", destructive_queries + 0
  }
' "$events_file"

awk -F '\t' '
  $3 == "Table_map" {
    text = $6
    id = text
    sub(/^table_id: /, "", id)
    sub(/ .*/, "", id)
    name = text
    sub(/^.*ai_app_generation\./, "", name)
    sub(/\).*$/, "", name)
    table[id] = name
    next
  }
  $3 == "Delete_rows" {
    text = $6
    id = text
    sub(/^table_id: /, "", id)
    sub(/ .*/, "", id)
    name = table[id]
    if (name ~ /^app_memory(_summary|_extract_cursor)?$/) {
      delete_rows++
    }
    next
  }
  $3 == "Query" {
    query = tolower($6)
    gsub(/`/, "", query)
    gsub(/[[:space:]]+/, " ", query)
    table_pattern = "(ai_app_generation\\.)?app_memory(_summary|_extract_cursor)?"
    destructive = "(truncate( table)?|drop table|delete from) " table_pattern "([ ;]|$)"
    if (query ~ destructive || query ~ /drop database ai_app_generation([ ;]|$)/) {
      destructive_queries++
    }
  }
  END {
    printf "retained_history_memory_delete_rows=%d\n", delete_rows + 0
    printf "retained_history_destructive_query_count=%d\n", destructive_queries + 0
  }
' "$history_file"

# FULL row image 中三张表的 isDelete 均为最后一列；按每个 UPDATE 的
# WHERE/SET 两段最后一个字段判定 0→1 逻辑删除，避免只统计 Delete_rows。
awk '
  function finish_update() {
    if (tracked && before_delete == 0 && after_delete == 1) {
      soft_delete_transitions++
    }
    tracked = 0
    section = ""
    before_delete = -1
    after_delete = -1
  }
  /^### UPDATE `/ {
    finish_update()
    tracked = ($0 ~ /`ai_app_generation`\.`app_memory(_summary|_extract_cursor)?`/)
    next
  }
  tracked && /^### WHERE$/ { section = "where"; next }
  tracked && /^### SET$/ { section = "set"; next }
  tracked && /^###   @[0-9]+=/ {
    value = $0
    sub(/^###   @[0-9]+=/, "", value)
    sub(/ .*/, "", value)
    if (section == "where") before_delete = value + 0
    if (section == "set") after_delete = value + 0
    next
  }
  /^### (INSERT INTO|DELETE FROM|UPDATE) / { finish_update() }
  END {
    finish_update()
    printf "retained_history_memory_soft_delete_transitions=%d\n", soft_delete_transitions + 0
  }
' "$decoded_history_file"

echo 'audit_boundary=检查点后审计与当前保留历史审计均截止于先冻结的file/position；不外推到未保留日志或其他daemon'
echo 'audit_exit=0'
