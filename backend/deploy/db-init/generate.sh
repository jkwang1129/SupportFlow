#!/usr/bin/env bash
#
# 生成"一键初始化"数据库脚本，用于在**全新环境**部署时初始化 6 个库。
#
# 做两件事（机械拼接，不手写 SQL，保证和仓库里正在用的 migrate 脚本同源）：
#   1. 每个库输出一份 <db>.sql = 基线(schema/<db>.sql) + 该库全部增量脚本（按 scripts/migrate-<db>.sh 的顺序）；
#   2. 输出一份 db-init.sql = 建库 + \connect + 上面 6 份内容，用 psql -f 一次跑完。
#
# 增量顺序直接从 scripts/migrate-*.sh 的 SCRIPTS=( ... ) 里读，所以以后加了迁移脚本，
# 重跑本脚本即可刷新"最新"初始化脚本，不会漏、不会错序。
#
# 用法：
#   bash backend/deploy/db-init/generate.sh
#   # 仓库结构不在默认相对位置时，用 SUPPORTFLOW_REPO_ROOT 指定仓库根目录（含 schema/ 与 scripts/）
#   SUPPORTFLOW_REPO_ROOT=/path/to/customer-service bash generate.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 仓库根目录（含 schema/ 与 scripts/）：默认从本脚本所在位置往上三级
# （backend/deploy/db-init → supportflow-backend → 仓库根）
ROOT="${SUPPORTFLOW_REPO_ROOT:-$(cd "$HERE/../../.." && pwd)}"
SCHEMA="$ROOT/schema"
MIGRATE="$ROOT/scripts"

# 库顺序：与部署/依赖顺序一致（建库脚本也按这个顺序输出）
DBS=(ai_db billing_db customer_db ops_db tenant_db user_db)

stamp="$(date '+%Y-%m-%d %H:%M')"

# 提取某个 migrate 脚本里 SCRIPTS=( ... ) 的文件名：去注释、去空行
increments_of() {
  awk '
    /^SCRIPTS=\(/ { f=1; next }
    f && /^\)/    { f=0 }
    f {
      sub(/^[ \t]+/, "")
      if ($0 == "" || $0 ~ /^#/) next
      print
    }
  ' "$1"
}

migrate_script_of() {
  case "$1" in
    ai_db)       echo "$MIGRATE/migrate-ai-db.sh" ;;
    billing_db)  echo "$MIGRATE/migrate-billing-db.sh" ;;
    customer_db) echo "$MIGRATE/migrate-customer-db.sh" ;;
    ops_db)      echo "$MIGRATE/migrate-ops-db.sh" ;;
    tenant_db)   echo "$MIGRATE/migrate-tenant-db.sh" ;;
    user_db)     echo "$MIGRATE/migrate-user-db.sh" ;;
    *)           echo "未知库：$1" >&2; exit 1 ;;
  esac
}

# 输出一个库的完整内容：基线 + 增量
emit_db() {
  local db="$1"
  local baseline="$SCHEMA/$db.sql"
  [ -f "$baseline" ] || { echo "缺少基线脚本：$baseline" >&2; exit 1; }

  echo "-- ==========================================================================="
  echo "-- 库：$db   （基线 schema/$db.sql）"
  echo "-- ==========================================================================="
  cat "$baseline"

  local migrate inc
  migrate="$(migrate_script_of "$db")"
  while IFS= read -r inc; do
    [ -z "$inc" ] && continue
    [ -f "$SCHEMA/$inc" ] || { echo "缺少增量脚本：$SCHEMA/$inc" >&2; exit 1; }
    echo
    echo "-- ---------------------------------------------------------------------------"
    echo "-- 增量：schema/$inc"
    echo "-- ---------------------------------------------------------------------------"
    cat "$SCHEMA/$inc"
  done < <(increments_of "$migrate")
}

# ---- 1) 每个库单独一份 ----
for db in "${DBS[@]}"; do
  out="$HERE/$db.sql"
  {
    echo "-- $db 初始化脚本（基线 + 增量），生成时间：$stamp"
    echo "-- 由 backend/deploy/db-init/generate.sh 从仓库 schema/ 与 scripts/migrate-*.sh 机械拼接，请勿手改。"
    emit_db "$db"
  } > "$out"
  echo "  → $(basename "$out")"
done

# ---- 2) 汇总成一份可一次执行的文件 ----
combined="$HERE/db-init.sql"
{
  echo "-- ============================================================================"
  echo "-- SupportFlow 智能客服 · 数据库一键初始化脚本（建库 + 结构 + 初始数据）"
  echo "-- 生成时间：$stamp"
  echo "-- 生成方式：backend/deploy/db-init/generate.sh 从仓库 schema/ + scripts/migrate-*.sh 机械拼接"
  echo "--"
  echo "-- 覆盖 6 个库：ai_db / billing_db / customer_db / ops_db / tenant_db / user_db"
  echo "-- 内容：建库 + 建表/索引/约束/注释 + 初始数据（套餐 plan、平台角色与权限点）"
  echo "--"
  echo "-- 用法（需要 psql；库名如需不同，改下面的 CREATE DATABASE 与 \\connect）："
  echo "--   PGPASSWORD=xxxx psql -h <host> -p 5432 -U <user> -d postgres -f db-init.sql"
  echo "--"
  echo "-- 前置条件："
  echo "--   1. 执行账号要有 CREATEDB 权限（建库），并且是目标对象属主；"
  echo "--   2. 目标 PostgreSQL 已安装 pgvector 扩展（customer_db / ai_db 用到 vector 类型）；"
  echo "--   3. 只用于**全新空库**：脚本按"先建库再建表"的顺序执行，重复执行会因表已存在而报错。"
  echo "-- ============================================================================"
  echo
  echo "\\set ON_ERROR_STOP on"
  echo
  echo "-- ---------------------------------------------------------------------------"
  echo "-- 一、建库（已存在则跳过）"
  echo "-- ---------------------------------------------------------------------------"
  for db in "${DBS[@]}"; do
    echo "SELECT 'CREATE DATABASE $db WITH ENCODING ''UTF8'' TEMPLATE template0'"
    echo " WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '$db')\\gexec"
  done
  for db in "${DBS[@]}"; do
    echo
    echo "\\connect $db"
    echo
    emit_db "$db"
  done
} > "$combined"
echo "  → $(basename "$combined")"

echo
echo "完成。共 ${#DBS[@]} 个库，输出目录：$HERE"
