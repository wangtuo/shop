#!/usr/bin/env bash
# W7 韧性整改后单节点容量拐点复扫：30 / 40 TPS constant-arrival-rate，
# 每档 3 分钟，档间冷却 90s（更新 PERF_REPORT §3）。
# 入口固定 kind ingress：--resolve shop.example.com:443:127.0.0.1（宿主映射）。
set -uo pipefail
K6_IMAGE=grafana/k6:latest
GW=192.168.65.254
EVDIR=deploy/loadtest/evidence
RUN_DIR=$(ls -td $EVDIR/final-* | head -1)
echo "证据目录: $RUN_DIR"
run_rate() {
  local rate=$1 log=$2
  echo "===== ${rate} TPS × 3min ====="
  docker run --rm -i --add-host shop.example.com:$GW "$K6_IMAGE" run \
    --insecure-skip-tls-verify - \
    -e BASE_URL=https://shop.example.com -e MODE=smoke \
    -e USER_POOL=2000 -e SKU_COUNT=50 \
    -e SMOKE_RATE=$rate -e SMOKE_DURATION=3m \
    -e SMOKE_PREALLOC_VUS=200 -e SMOKE_MAX_VUS=800 \
    < deploy/loadtest/k6-order.js 2>&1 | tee "$log"
}
run_rate 30 "$RUN_DIR/11-k6-30tps-rescan.log"
echo "冷却 90s ..."; sleep 90
run_rate 40 "$RUN_DIR/11-k6-40tps-rescan.log"
echo "复扫完成: $RUN_DIR/11-k6-{30,40}tps-rescan.log"
