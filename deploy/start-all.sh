#!/usr/bin/env bash
# 本地启动全部 8 个 Spring Boot 应用（网关 + 7 服务），日志输出到 deploy/logs/<app>.log
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/deploy/logs"
mkdir -p "$LOG_DIR"

APPS=(
  "shop-gateway:8080"
  "shop-user-service:8081"
  "shop-product-service:8082"
  "shop-marketing-service:8083"
  "shop-order-service:8084"
  "shop-pay-service:8085"
  "shop-settlement-service:8086"
  "shop-aftersale-service:8087"
)

for item in "${APPS[@]}"; do
  app="${item%%:*}"
  jar="$ROOT/$app/target/$app.jar"
  if [[ ! -f "$jar" ]]; then
    echo "[ERROR] $jar 不存在，请先执行 mvn clean package -DskipTests"
    exit 1
  fi
  echo "starting $app ..."
  nohup java -Xms256m -Xmx512m -jar "$jar" \
    --spring.config.additional-location="optional:file:$ROOT/deploy/local/" \
    > "$LOG_DIR/$app.log" 2>&1 &
  echo $! > "$LOG_DIR/$app.pid"
done

echo "全部已后台启动，日志：$LOG_DIR/*.log；停止：deploy/stop-all.sh"
