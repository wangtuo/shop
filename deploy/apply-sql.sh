#!/usr/bin/env bash
# =============================================================================
# apply-sql.sh — shop 七业务库 schema 唯一应用入口（DEPLOY-1）
#
# 为什么存在：
#   docker-compose 的 /docker-entrypoint-initdb.d 只在数据卷首次初始化时执行，
#   历史上只挂了 00 建库 + 7 份 V2；V3+（含缺口修复波全部脚本）重建不自动执行、
#   kind 环境更无 SQL 引导。本脚本按库循环应用 sql/ 全量版本，带 changelog
#   账本，幂等可重复执行；fresh init 与存量库两条路径都收敛到这里。
#
# 幂等模型：
#   - 所有迁移脚本本身均为 information_schema 守卫的幂等 DDL（V2 为裸 CREATE，
#     仅允许在空库由本脚本首跑）。
#   - 每个业务库维护 t_shop_schema_history(script_name, checksum, applied_at)，
#     已入账脚本跳过；checksum 变化直接失败（防静默漂移）。
#   - BASELINE=1：不执行 SQL，只把 sql/ 现状全部登记入账（用于本脚本上线前
#     已手工应用过全部迁移的存量库；fresh 库禁止使用）。
#
# 执行通道（自动探测，可用环境变量覆盖）：
#   - 容器内（mysql 镜像 entrypoint 调用）：直接使用本地 mysql 客户端（socket）。
#   - 宿主：优先 PATH 中的 mysql；否则 docker exec -i ${MYSQL_CONTAINER} mysql。
# 可选变量：MYSQL_CONTAINER=shop-mysql MYSQL_USER=root MYSQL_PASSWORD=root
#           SQL_DIR=/path/to/sql BASELINE=0
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -n "${SQL_DIR:-}" ]]; then
  :
elif [[ -d "/opt/shop-sql" ]]; then
  SQL_DIR="/opt/shop-sql"                 # mysql 容器内挂载点
else
  SQL_DIR="$(cd "$SCRIPT_DIR/../sql" && pwd)"
fi

MYSQL_CONTAINER="${MYSQL_CONTAINER:-shop-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
BASELINE="${BASELINE:-0}"

DATABASES=(shop_user shop_product shop_marketing shop_order shop_pay shop_settlement shop_aftersale)

if [[ ! -d "$SQL_DIR" ]]; then
  echo "[apply-sql] SQL_DIR 不存在: $SQL_DIR" >&2
  exit 1
fi

# ---- mysql 执行通道 ----------------------------------------------------------
# 注意：mysql 客户端默认会读取 stdin；在 while-read 循环中调用会吞掉管道里剩余
# 的迁移文件名。故 -e 系列统一 </dev/null，只有 sql_run_file 需要文件重定向。
# 通道（含 mysql 镜像 initdb 临时服务器阶段）统一用 root+密码：8.0 entrypoint
# 自己也是 socket -uroot -p$MYSQL_ROOT_PASSWORD，socket 并不免密。
if command -v mysql >/dev/null 2>&1; then
  sql_exec() { mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$@" </dev/null; }
elif command -v docker >/dev/null 2>&1; then
  docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER" \
    || { echo "[apply-sql] mysql 容器未运行: $MYSQL_CONTAINER" >&2; exit 1; }
  sql_exec() { docker exec -i "$MYSQL_CONTAINER" mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$@" </dev/null; }
else
  echo "[apply-sql] 找不到 mysql 客户端或 docker" >&2
  exit 1
fi

# 执行 SQL 文件：与 sql_exec 不同，stdin 必须接文件
sql_run_file() { # db file（db 可为空串）
  local db="$1" file="$2"
  if command -v mysql >/dev/null 2>&1; then
    mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" $db < "$file"
  else
    docker exec -i "$MYSQL_CONTAINER" mysql --default-character-set=utf8mb4 \
      -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" $db < "$file"
  fi
}

wait_mysql() {
  local i
  for i in $(seq 1 60); do
    if sql_exec -N -e "SELECT 1" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "[apply-sql] MySQL 60 次探活仍不可用" >&2
  return 1
}

# ---- 迁移清单：版本号 → 文件（同版本 common 先于 domain） --------------------
# 输出：版本号|类别(common/domain)|库名(common 用 '-')|绝对路径
plan_files() {
  local db="$1" domain
  domain="${db#shop_}"
  {
    [[ -d "$SQL_DIR/common" ]] && find "$SQL_DIR/common" -maxdepth 1 -name 'V*.sql' -type f
    [[ -d "$SQL_DIR/$domain" ]] && find "$SQL_DIR/$domain" -maxdepth 1 -name 'V*.sql' -type f
  } | while read -r f; do
      local ver cls
      ver="$(basename "$f" | sed -E 's/^V([0-9]+)__.*$/\1/')"
      if [[ "$(dirname "$f")" == */common ]]; then cls=0; else cls=1; fi
      printf '%s\t%s\t%s\n' "$ver" "$cls" "$f"
    done | sort -k1,1n -k2,2n | while IFS=$'\t' read -r ver cls f; do
      # V6（outbox UK）内含 7 库 USE 循环，只能整体执行一次：交给调用方单列
      if [[ "$(basename "$f")" == V6__* ]]; then continue; fi
      echo "$f"
    done
}

HISTORY_DDL='
CREATE TABLE IF NOT EXISTS t_shop_schema_history (
  script_name  VARCHAR(255) NOT NULL,
  checksum_sha CHAR(64)     NOT NULL,
  applied_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (script_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT="schema 迁移历史账本（apply-sql.sh）";'

history_has() { # db script
  sql_exec -N "$1" -e "SELECT 1 FROM t_shop_schema_history WHERE script_name='$2' LIMIT 1" 2>/dev/null
}
history_get_checksum() {
  sql_exec -N "$1" -e "SELECT checksum_sha FROM t_shop_schema_history WHERE script_name='$2' LIMIT 1" 2>/dev/null
}
history_mark() { # db script checksum
  sql_exec "$1" -e "INSERT INTO t_shop_schema_history(script_name,checksum_sha) VALUES('$2','$3') \
    ON DUPLICATE KEY UPDATE checksum_sha=VALUES(checksum_sha)"
}

sha64() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}';
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

apply_one() { # db file
  local db="$1" file="$2" name ck
  name="$(basename "$file")"
  ck="$(sha64 "$file")"
  if [[ -n "$(history_has "$db" "$name")" ]]; then
    local old
    old="$(history_get_checksum "$db" "$name")"
    if [[ "$old" != "$ck" ]]; then
      echo "[apply-sql] !! $db / $name checksum 漂移（账本 $old vs 当前 ${ck}），拒绝继续" >&2
      exit 1
    fi
    echo "[apply-sql] skip  $db/${name}（已入账）"
    return 0
  fi
  if [[ "$BASELINE" == "1" ]]; then
    history_mark "$db" "$name" "$ck"
    echo "[apply-sql] base  $db/$name"
    return 0
  fi
  echo "[apply-sql] exec  $db/$name"
  sql_run_file "$db" "$file"
  history_mark "$db" "$name" "$ck"
}

wait_mysql

echo "[apply-sql] SQL_DIR=$SQL_DIR BASELINE=$BASELINE"
for db in "${DATABASES[@]}"; do
  sql_exec "$db" -e "$HISTORY_DDL"
done

# ---- 逐库应用（common V3/V5 随各库执行；domain 跟随） ------------------------
for db in "${DATABASES[@]}"; do
  while IFS= read -r file; do
    [[ -n "$file" ]] && apply_one "$db" "$file"
  done < <(plan_files "$db")
done

# ---- common V6：自带 7 库 USE 循环，整体执行一次，七库各记账 -----------------
V6="$(find "$SQL_DIR/common" -maxdepth 1 -name 'V6__*.sql' -type f | head -n1 || true)"
if [[ -n "$V6" ]]; then
  name="$(basename "$V6")"; ck="$(sha64 "$V6")"
  if [[ "$BASELINE" == "1" ]]; then
    for db in "${DATABASES[@]}"; do history_mark "$db" "$name" "$ck"; done
    echo "[apply-sql] base  ${name}（7 库）"
  else
    missing=0
    for db in "${DATABASES[@]}"; do [[ -z "$(history_has "$db" "$name")" ]] && missing=1; done
    if [[ "$missing" == "0" ]]; then
      echo "[apply-sql] skip  ${name}（7 库均已入账）"
    else
      echo "[apply-sql] exec  ${name}（内含 7 库循环）"
      sql_run_file "" "$V6"
      for db in "${DATABASES[@]}"; do history_mark "$db" "$name" "$ck"; done
    fi
  fi
fi

echo "[apply-sql] 全部数据库迁移检查完毕：${DATABASES[*]}"
