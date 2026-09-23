#!/usr/bin/env bash
# =============================================================================
# 10-apply-sql.sh — MySQL 镜像首次初始化时执行（/docker-entrypoint-initdb.d）
#
# 00-create-databases.sql 先建 7 库；本脚本随后把 sql/ 全量版本（含 V3+ 缺口
# 修复波脚本）逐库应用。临时 server 以本地 socket 提供 root 免密连接，
# apply-sql.sh 会自动探测 /.dockerenv 走 socket 模式。
# 存量数据卷（非首次初始化）不会触发本脚本，由宿主在升级时手工运行
# deploy/apply-sql.sh（final-acceptance MIDDLEWARE 阶段会自动调用）。
# =============================================================================
set -euo pipefail
export SQL_DIR=/opt/shop-sql
echo "[initdb] 10-apply-sql: 应用全量 schema（${SQL_DIR}）"
/opt/shop/apply-sql.sh
