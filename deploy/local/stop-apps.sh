#!/usr/bin/env bash
# 停止 deploy/local/start-apps.sh 启动的全部进程
# 语义：pidfile wrapper 发 TERM → 等待最多 30s 优雅停机 → 仍存活的 shop jar 强杀（防 NIO 卡死）
set -uo pipefail
cd "$(dirname "$0")/../.."
PIDDIR=$(pwd)/deploy/local/pids
for f in "$PIDDIR"/*.pid; do
  [ -e "$f" ] || continue
  pid=$(cat "$f"); svc=$(basename "$f" .pid)
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" && echo "TERM $svc ($pid)"
  else
    echo "$svc ($pid) 已不在运行"
  fi
done

for _ in $(seq 1 15); do
  alive=0
  for f in "$PIDDIR"/*.pid; do
    [ -e "$f" ] || continue
    pid=$(cat "$f" 2>/dev/null)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && alive=1
  done
  [ "$alive" = 0 ] && break
  sleep 2
done

for svcj in shop-user-service shop-product-service shop-marketing-service \
           shop-order-service shop-pay-service shop-settlement-service shop-aftersale-service shop-gateway; do
  jpid=$(pgrep -f "$svcj/target/$svcj.jar" || true)
  if [ -n "$jpid" ]; then
    echo "优雅停机超时，KILL $svcj ($jpid)"
    kill -KILL $jpid 2>/dev/null || true
  fi
done
rm -f "$PIDDIR"/*.pid
echo "全部宿主应用已停止"
