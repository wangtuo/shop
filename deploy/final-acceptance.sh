#!/usr/bin/env bash
# =============================================================================
# 最终生产级验收总闸门（一套完全相同的最终产物：最终源码 → 最终 jar → 最终镜像）
#
# 阶段顺序（每阶段独立留证，任一失败即终止，退出码非 0）：
#   1. UNIT       全量单测 mvn clean test（8 模块 + framework/common/api，~900 用例）
#   2. PACKAGE    mvn clean package -DskipTests 产出全部最终 jar
#   3. MIDDLEWARE 宿主 docker compose 中间件健康（MySQL/Redis/RocketMQ/Nacos）
#      3b. R4-19：静默残留 kind 工作负载（两形态共用中间件，同名 MQ 消费组会串扰）
#   4. HOSTAPPS   deploy/local/start-apps.sh 起 8 个宿主应用（8081..8087 + 网关 8080）
#   5. E2E        前置 R4-28 七库 outbox 到期积压排空；shop-e2e 59 用例全绿（-Dshop.e2e=true，经网关 8080）
#   6. CHAOS      deploy/loadtest/chaos.sh（Redis/RocketMQ 宕机 + outbox 自愈）
#   7. KIND_IMAGE build-and-load-kind.sh：用同一批最终 jar 构建 8 镜像并 load
#      7b. R4-19：停止宿主 8 应用（保证 kind 部署/压测期间只有一个形态在线）
#   8. KIND_DEPLOYkubectl apply 生产清单 + kind 覆盖层；rollout 完成
#   9. HA         deploy/kubernetes/ha-check.sh（6 节全绿）
#  10. K6_SEED    seed.sh 灌 2000 压测用户 + 50 SKU；seed-oversell.sh 建 20 库存秒杀
#  11. K6_SMOKE   20 TPS × 3min 恒定到达（阈值成功率>0.99 / p95<800ms，exit 必须 0）
#  12. K6_OVERSELL 20 库存 / 40 并发（winners=losers=paid=20 硬断言，exit 必须 0）
#
# 用法：
#   bash deploy/final-acceptance.sh                # 全量（约 1.5~2 小时）
#   SKIP_STAGES="HOSTAPPS" bash deploy/final-acceptance.sh   # 宿主应用已在跑
#   ONLY_STAGES="HA,K6_SMOKE,K6_OVERSELL" bash deploy/final-acceptance.sh
#
# 铁律：kubectl 只允许 --context kind-kind（脚本内强制）；真实生产 context 永不触碰。
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
CTX=kind-kind
# EVIDENCE_DIR 可显式指定（断链恢复 ONLY_STAGES 续跑时复用同一目录，保证同一套最终产物证据不拆目录）
EVDIR="${EVIDENCE_DIR:-deploy/loadtest/evidence/final-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$EVDIR"
STAMP=$(date +%H:%M:%S)

# ---- SDKMAN（其初始化脚本有未定义变量引用，必须临时关 nounset）----
set +u; source ~/.sdkman/bin/sdkman-init.sh; set -u

K="kubectl --context $CTX"
DOCKER_GW=192.168.65.254      # Docker Desktop Mac 网关，k6 容器回连宿主 443
K6_IMAGE=grafana/k6:latest
CHANNEL_SECRET=${CHANNEL_SECRET:-kind-ha-mock-wechat-secret-2026-0001}
USER_POOL=${USER_POOL:-2000}
SKU_COUNT=${SKU_COUNT:-50}
OVERSELL_STOCK=${OVERSELL_STOCK:-20}
# 宿主形态网关端口：默认 8080；若被本机其他进程占用，执行方经 SHOP_GATEWAY_PORT 避让。
# start-apps.sh 与网关 yml 读同一环境变量，E2E/CHAOS 随之指向该端口（K8s 形态不受影响）。
GATEWAY_PORT=${SHOP_GATEWAY_PORT:-8080}
export SHOP_GATEWAY_PORT=$GATEWAY_PORT

ALL_STAGES=(UNIT PACKAGE MIDDLEWARE HOSTAPPS E2E CHAOS KIND_IMAGE KIND_DEPLOY HA K6_SEED K6_SMOKE K6_OVERSELL)
if [ -n "${ONLY_STAGES:-}" ]; then
  IFS=',' read -ra RUN_STAGES <<< "$ONLY_STAGES"
else
  RUN_STAGES=("${ALL_STAGES[@]}")
fi
should_run() {
  [ -n "${ONLY_STAGES:-}" ] && { printf '%s\n' "${RUN_STAGES[@]}" | grep -qx "$1" && return 0 || return 1; }
  printf '%s\n' "${SKIP_STAGES:-}" | tr ',' '\n' | grep -qx "$1" && return 1
  return 0
}
stage() { printf '\n===== [%s] %s =====\n' "$(date +%H:%M:%S)" "$1"; }
die() { echo "FATAL: $*" | tee "$EVDIR/FAILED.${1:-stage}"; exit 1; }

# ---- 0. context 硬保护：只允许 kind-kind ----
kubectl config current-context 2>/dev/null | grep -qx "$CTX" \
  || die "当前 kubectl context 不是 ${CTX}（安全保护，拒绝触碰任何其他集群）"

# ---- 1. UNIT 全量单测 ----
if should_run UNIT; then
  stage "UNIT 全量单测"
  mvn -B clean test 2>&1 | tee "$EVDIR/01-unit.log"
  grep -qE "BUILD SUCCESS" "$EVDIR/01-unit.log" || die UNIT
  grep -E "Tests run: [0-9]+, Failures: 0, Errors: 0" "$EVDIR/01-unit.log" | tail -1
fi

# ---- 2. PACKAGE ----
if should_run PACKAGE; then
  stage "PACKAGE 最终 jar"
  mvn -B clean package -DskipTests 2>&1 | tee "$EVDIR/02-package.log"
  grep -qE "BUILD SUCCESS" "$EVDIR/02-package.log" || die PACKAGE
  # jar 命名与 build-and-load-kind.sh MODULES 逐字一致
  for jar in shop-gateway/target/shop-gateway.jar \
             shop-user-service/target/shop-user-service.jar \
             shop-product-service/target/shop-product-service.jar \
             shop-marketing-service/target/shop-marketing-service.jar \
             shop-order-service/target/shop-order-service.jar \
             shop-pay-service/target/shop-pay-service.jar \
             shop-settlement-service/target/shop-settlement-service.jar \
             shop-aftersale-service/target/shop-aftersale-service.jar; do
    [ -f "$jar" ] || die "jar 缺失: $jar"
  done
fi

# ---- 3. MIDDLEWARE ----
if should_run MIDDLEWARE; then
  stage "MIDDLEWARE docker compose 健康"
  ( cd deploy && docker compose up -d mysql redis rmq-namesrv rmq-broker nacos ) 2>&1 | tee "$EVDIR/03-middleware.log"
  for i in $(seq 1 60); do
    docker exec shop-mysql mysqladmin -uroot -proot ping 2>/dev/null | grep -q alive && break
    sleep 3
  done
  docker exec shop-mysql mysqladmin -uroot -proot ping 2>/dev/null | grep -q alive || die "MySQL 未就绪"
  # DEPLOY-1：七库 schema 全量版本收敛到 apply-sql.sh（账本幂等，fresh 库由 initdb 10 脚本执行）
  stage "MIDDLEWARE schema 迁移（apply-sql.sh）"
  bash "$(dirname "$0")/apply-sql.sh" 2>&1 | tee "$EVDIR/03-apply-sql.log"
  # kind 业务 Pod 以 prod profile 启动，DB 口令必须非 root/非弱口令（R-Z2 fail-fast）。
  # 幂等确保应用账号 shop_app + 七库授权存在（fresh initdb 由 20-kind-app-user.sql 完成，
  # 存量数据卷不触发 initdb，故此处统一补执行；CREATE USER IF NOT EXISTS 不覆盖既有口令）。
  docker exec -i shop-mysql mysql -uroot -proot < "$(dirname "$0")/mysql/20-kind-app-user.sql" 2>/dev/null
  docker exec shop-mysql mysql -ushop_app -p'K1nd-ShopApp-2026!x9' -h127.0.0.1 -N -e "SELECT 1" >/dev/null 2>&1 \
    || die "shop_app 应用账号未就绪（检查 deploy/mysql/20-kind-app-user.sql）"
  docker exec shop-redis redis-cli ping 2>/dev/null | grep -q PONG || die "Redis 未就绪"
  # broker 通告 IP 必须是宿主 LAN IP（kind Pod 三平面可达）；127.0.0.1 仅供宿主进程。
  # broker.conf 写作 "brokerIP1 = 127.0.0.1"（= 两侧带空格），cut 后必须 trim，
  # 否则前导空格使 case 模式失配、kind 前的自动重建静默不触发（W7 实证）。
  BROKER_IP=$(docker exec shop-rmq-broker sh -c "grep '^brokerIP1' /tmp/broker.conf 2>/dev/null | cut -d= -f2" 2>/dev/null | tr -d '[:space:]' || true)
  echo "brokerIP1=${BROKER_IP:-未知}"
  case "${BROKER_IP:-}" in 127.0.0.1|localhost|"")
    LAN_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)
    echo "WARN: broker 通告地址 ${BROKER_IP:-空}，kind Pod 不可达；需用 SHOP_BROKER_IP=$LAN_IP 重建 broker 并重启全部消费服务 Pod"
    [ -n "$LAN_IP" ] && ( cd deploy && SHOP_BROKER_IP="$LAN_IP" docker compose up -d --force-recreate rmq-broker ) \
      && echo "已按 LAN IP 重建；下一阶段 KIND_DEPLOY 后必须重启 6 个消费服务（push 消费者不自动刷新路由）"
    ;;
  esac
  # R4-17 容量守卫①：本地 MySQL 必须能承载 16 Pod 的 Druid 池（kind 验收 cap 后 ~140
  # 连接；历史连接/宿主 JVM 同库时需要更多）。出厂 151 会在 smoke 下 1040 连锁。
  MC=$(docker exec shop-mysql mysql -uroot -proot -N -e "SHOW VARIABLES LIKE 'max_connections'" 2>/dev/null | awk '{print $2}')
  if [ "${MC:-0}" -lt 400 ]; then
    echo "WARN: MySQL max_connections=${MC} < 400，运行时放宽到 600（持久化由 compose --max-connections=600 在重建后生效）"
    docker exec shop-mysql mysql -uroot -proot -e "SET GLOBAL max_connections=600" 2>/dev/null \
      || die "MySQL max_connections 提升失败"
  fi
  # R4-17 容量守卫②：RocketMQ 5.3.1 写门硬上限 0.90（源码不可配，含 0.85 迟滞）。
  # broker 容器内 store 盘使用率必须 <0.90，否则全部生产返回 50001 disk full，
  # outbox 堆积、订单链路超时。超水位直接 die 并给出安全回收清单（禁止删 shop 数据卷）。
  # R4-19：store 现为命名卷 shop-rmq-data，采样口径是 Docker Desktop VM overlay 盘
  # （非 Mac 宿主盘），正常水位 ~34%；若仍越线说明 VM 盘（含 build cache/镜像）需回收。
  BR_RATIO=$(docker exec shop-rmq-broker sh -c "df /home/rocketmq/store 2>/dev/null | awk 'NR==2{printf \"%d\", \$3*100/\$2}'" 2>/dev/null || echo 0)
  echo "broker store 磁盘使用率=${BR_RATIO}%（写门硬上限 90%）"
  if [ "${BR_RATIO}" -ge 90 ]; then
    cat >&2 <<EOF
FATAL: broker 磁盘水位 ${BR_RATIO}% >= 90%，RocketMQ 将拒写（50001 disk full）。
安全回收（不得删 shop-mysql-data/shop-redis-data/shop-grafana-data/shop-rmq-data
等数据卷或在用镜像）：
  docker builder prune -af                       # build cache（VM 盘可回收大头）
  docker image prune -f                          # dangling 镜像
  docker volume prune -f                         # orphan 卷（shop 在用卷不会被删）
  docker exec shop-rmq-broker rm -rf /home/rocketmq/logs/rocketmqlogs/otherdays/*
回收后用 SHOP_BROKER_IP=<LAN IP> docker compose up -d --force-recreate rmq-broker 冷启动（水位标志有迟滞）。
EOF
    die "RocketMQ broker 磁盘水位 ${BR_RATIO}% ≥ 90%"
  fi
fi

# ---- 4. HOSTAPPS ----
if should_run HOSTAPPS; then
  # R4-19 环境隔离①：宿主形态与 kind 形态共用同一套 MySQL/Redis/RocketMQ。
  # Nacos 注册靠 group 隔离（kind=shop-ha-kind 注册 10.244.x，宿主=DEFAULT_GROUP），
  # 但 MQ 消费组同名共享——残留 kind Pod 会偷走宿主 E2E 的支付/订单事件；且网络不对称
  # （宿主→kind Pod IP 超时不可达，反向可达），串扰表现为 E2E 大面积等待超时与 10008。
  # 进 HOSTAPPS 前把 shop 命名空间残留工作负载全部静默（HPA 先删，否则会把副本拉回；
  # KIND_DEPLOY 阶段随生产清单原样重建）。无 kind 残留时幂等跳过。
  stage "HOSTAPPS 前置：静默残留 kind 工作负载（R4-19 环境隔离）"
  if $K get ns shop >/dev/null 2>&1; then
    HPAS=$($K -n shop get hpa -o jsonpath='{.items[*].metadata.name}' 2>/dev/null || true)
    if [ -n "$HPAS" ]; then
      echo "删除 kind shop HPA（防止 scale 0 被拉回；KIND_DEPLOY 随清单重建）: $HPAS"
      $K -n shop delete hpa $HPAS --ignore-not-found 2>&1 | tee "$EVDIR/03b-kind-silence.log"
    fi
    $K -n shop scale deploy --all --replicas=0 2>&1 | tee -a "$EVDIR/03b-kind-silence.log"
    $K -n shop scale statefulset --all --replicas=0 2>/dev/null | tee -a "$EVDIR/03b-kind-silence.log" || true
    for i in $(seq 1 45); do
      POD_CNT=$($K -n shop get pods --no-headers 2>/dev/null | grep -vc Terminating || true)
      [ "${POD_CNT:-0}" = 0 ] && break
      sleep 4
    done
    $K -n shop get pods 2>/dev/null | tee -a "$EVDIR/03b-kind-silence.log"
    echo "kind 工作负载已静默（R4-19）"
  else
    echo "（shop 命名空间不存在，无 kind 残留需静默）"
  fi
  stage "HOSTAPPS 启动宿主 8 应用"
  bash deploy/local/start-apps.sh ${START_APPS_EXTRA:-} 2>&1 | tee "$EVDIR/04-hostapps.log"
  for i in $(seq 1 90); do
    up=0; for p in 8081 8082 8083 8084 8085 8086 8087; do
      curl -sf -o /dev/null -m 2 "http://localhost:$p/actuator/health/liveness" && up=$((up+1))
    done
    curl -sf -o /dev/null -m 2 "http://localhost:$GATEWAY_PORT/actuator/health/liveness" && [ "$up" = 7 ] && break
    sleep 4
  done
  curl -sf -o /dev/null "http://localhost:$GATEWAY_PORT/actuator/health/liveness" || die "网关未就绪(:$GATEWAY_PORT)"
fi

# ---- 5. E2E ----
if should_run E2E; then
  # R4-28：背靠背复跑时，上一轮（kind 形态）遗留的 status=0 到期 outbox 行会在宿主应用
  # 启动后由 relay 按 FIFO 集中补投（实测 5363 行 r426 压测旧消息补投约 6 分钟）；E2E 新事件
  # 排在积压波之后，20s 业务轮询窗内 stage 不推进会假失败（系统最终一致，非代码缺陷）。
  # E2E 前强制七库到期积压排空到 0，超时（15 分钟）判环境未恢复失败。
  stage "E2E 前置：七库 outbox 到期积压排空（R4-28）"
  OUTBOX_DBS="shop_user shop_product shop_order shop_pay shop_settlement shop_marketing shop_aftersale"
  : > "$EVDIR/04b-outbox-drain.log"
  drained=0
  for i in $(seq 1 90); do
    sql=""
    for db in $OUTBOX_DBS; do
      [ -n "$sql" ] && sql="$sql UNION ALL "
      sql="$sql SELECT '$db', COUNT(*) FROM $db.t_mq_outbox WHERE status=0 AND deliver_at<=NOW()"
    done
    line=$(docker exec shop-mysql mysql -uroot -proot -N -e "$sql;" 2>/dev/null)
    total=$(printf '%s\n' "$line" | awk '{s+=$2} END {print s+0}')
    echo "[$(date +%H:%M:%S)] due_pending=$total  $(printf '%s' "$line" | tr '\n' ' ')" | tee -a "$EVDIR/04b-outbox-drain.log"
    if [ "$total" = 0 ]; then drained=1; break; fi
    sleep 10
  done
  [ "$drained" = 1 ] || die "outbox 到期积压 15 分钟未排空（上一轮遗留消息补投未完，E2E 会假失败）"

  stage "E2E 身份引导（bootstrap-admin + 商户开通入驻，幂等）"
  # 平台/商户身份无法公开自助注册（E2E_REPORT 缺口 #1）；fresh 库必须先引导，否则
  # World.assumeReady() 假设不成立会把 10 个场景类整体 skip（假绿）。
  # 仅引导身份与类目/品牌主数据，不灌压测用户池/商品（USER_POOL=0 SKU_COUNT=0）。
  BASE_URL=http://localhost:$GATEWAY_PORT USER_POOL=0 SKU_COUNT=0 \
    bash deploy/loadtest/seed.sh 2>&1 | tee "$EVDIR/05a-e2e-seed.log"
  stage "E2E shop-e2e 全量黑盒用例"
  mvn -B -pl shop-e2e test -Dshop.e2e=true "-Dshop.gateway=http://localhost:$GATEWAY_PORT" \
    -Dshop.admin.account=admin -Dshop.admin.password=Admin@12345 \
    -Dshop.merchant.account=load_merchant -Dshop.merchant.password=Load@12345 \
    2>&1 | tee "$EVDIR/05-e2e.log"
  grep -qE "BUILD SUCCESS" "$EVDIR/05-e2e.log" || die E2E
  # 防假绿：59 用例门——环境缺身份时 10 个场景类会被 Assumption 整体跳过
  RUN_CNT=$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$EVDIR/05-e2e.log" | tail -1 | sed -E 's/.*Tests run: ([0-9]+).*/\1/')
  echo "E2E 实际执行 $RUN_CNT 例（门槛 59，含 2 例环境门控 skip 属预期）"
  [ "${RUN_CNT:-0}" -ge 59 ] || die "E2E 实际执行仅 $RUN_CNT 例（<59，疑似身份/环境缺失导致整类 skip 的假绿）"
  grep -E "Tests run: [0-9]+, Failures: 0, Errors: 0" "$EVDIR/05-e2e.log" | tail -1
fi

# ---- 6. CHAOS（宿主形态：中间件宕机注入 + outbox 自愈）----
if should_run CHAOS; then
  stage "CHAOS 混沌验收（宿主形态）"
  BASE_URL=http://localhost:$GATEWAY_PORT bash deploy/loadtest/chaos.sh 2>&1 | tee "$EVDIR/06-chaos.log"
  # 注意宿主混沌脚本输出「混沌结果:」，kind HA 脚本输出「HA 结果:」，两个门不能混用同一条正则
  # （W7 实证：此处曾误抄 ha-check 的「HA 结果」，导致 chaos.sh 实测 PASS=30 FAIL=0 仍被 die）。
  grep -qE "混沌结果: PASS=.* FAIL=0" "$EVDIR/06-chaos.log" || die CHAOS
fi

# ---- 7. KIND_IMAGE（同一批最终 jar）----
if should_run KIND_IMAGE; then
  stage "KIND_IMAGE 构建并加载 8 镜像"
  TAG=2.0.0 bash deploy/kubernetes/build-and-load-kind.sh 2>&1 | tee "$EVDIR/07-kind-image.log"
  grep -q "完成" "$EVDIR/07-kind-image.log" || die KIND_IMAGE
fi

# ---- 8. KIND_DEPLOY ----
if should_run KIND_DEPLOY; then
  # R4-19 环境隔离②：kind 压测期间宿主 8 应用必须全部停止——两形态共用同一套
  # MySQL/Redis/RocketMQ，宿主消费者以同名 MQ 消费组偷走 kind Pod 的支付/订单事件
  # （W7 实证 K6 前夜宿主 8081-8087 全 DOWN 才闭合；未停时 E2E/K6 表现为消息消失），
  # 宿主定时任务（outbox 转发表/对账/延时关单）也会跨形态处理同一批行。
  # stop-apps.sh 幂等（R4-4），HOSTAPPS 跳过时同样执行，保证「同一时刻只有一个形态在线」。
  stage "KIND_DEPLOY 前置：停止宿主 8 应用（R4-19 环境隔离）"
  bash deploy/local/stop-apps.sh 2>&1 | tee "$EVDIR/07b-stop-hostapps.log" || true
  stage "KIND_DEPLOY 应用生产清单 + kind 覆盖层"
  # 顺序关键：kind 覆盖层里的 ROCKETMQ_ENDPOINTS 可能是上一网络的宿主 IP（kind 单节点
  # Pod 经宿主 LAN IP 回连 broker proxy）。必须「基础配置 → kind 覆盖 → 按当前 LAN IP
  # patch ConfigMap → 再创建业务 Deployment」，否则 16 个 Pod 首次启动就拿到死端点，
  # MQ 消费者注册失败且 env 不随 ConfigMap 热更新（W7 实证：切 Wi-Fi 后 Tailscale IP 漂移）。
  $K apply -f deploy/kubernetes/00-namespace-config.yaml 2>&1 | tee "$EVDIR/08-kind-deploy.log"
  $K apply -f deploy/kubernetes/kind/00-kind-infra.yaml 2>&1 | tee -a "$EVDIR/08-kind-deploy.log"
  if ! $K -n shop get secret shop-infra-secret >/dev/null 2>&1; then
    $K apply -f deploy/kubernetes/kind/05-kind-secret.yaml 2>&1 | tee -a "$EVDIR/08-kind-deploy.log"
  fi
  LAN_IP=${SHOP_BROKER_IP:-$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)}
  if [ -n "$LAN_IP" ]; then
    echo "patch shop-infra-config ROCKETMQ_ENDPOINTS=$LAN_IP:18081"
    $K -n shop patch configmap shop-infra-config --type merge \
      -p "{\"data\":{\"ROCKETMQ_ENDPOINTS\":\"$LAN_IP:18081\"}}" 2>&1 | tee -a "$EVDIR/08-kind-deploy.log"
  else
    echo "WARN: 未发现宿主 LAN IP，Pod 将沿用 kind 覆盖层内的 ROCKETMQ_ENDPOINTS"
  fi
  $K apply -f deploy/kubernetes/10-services.yaml 2>&1 | tee -a "$EVDIR/08-kind-deploy.log"
  $K apply -f deploy/kubernetes/20-tls-ingress.yaml -f deploy/kubernetes/30-networkpolicy.yaml 2>&1 | tee -a "$EVDIR/08-kind-deploy.log" || true
  # 单节点 kind：硬反亲和会让第 2 副本 Pending；ha-check.sh 内含完整的调度兼容补丁
  # （preferred + 旧 RS 缩 0），这里先做一次 rollout status 观测，真正收敛在 HA 阶段完成。
  for d in gateway user product marketing order pay settlement aftersale; do
    $K -n shop rollout status deploy/shop-$d-service --timeout=120s 2>&1 | tail -1 || true
  done
  $K -n shop get pods | tee "$EVDIR/08-pods.txt"
fi

# ---- 9. HA ----
if should_run HA; then
  stage "HA kind 高可用六节"
  bash deploy/kubernetes/ha-check.sh 2>&1 | tee "$EVDIR/09-ha.log"
  grep -qE "HA 结果: PASS=.* FAIL=0" "$EVDIR/09-ha.log" || die HA
fi

# ---- 10. K6_SEED ----
if should_run K6_SEED; then
  stage "K6_SEED 压测用户池 + 秒杀种子"
  USER_POOL=$USER_POOL BASE_URL=https://shop.example.com \
    CURL_EXTRA="-k --resolve shop.example.com:443:127.0.0.1" \
    bash deploy/loadtest/seed.sh 2>&1 | tee "$EVDIR/10-seed.log"
  STOCK=$OVERSELL_STOCK BASE_URL=https://shop.example.com \
    CURL_EXTRA="-k --resolve shop.example.com:443:127.0.0.1" \
    bash deploy/loadtest/seed-oversell.sh > "$EVDIR/10-oversell-seed.env" 2>>"$EVDIR/10-oversell-seed.log"
  cat "$EVDIR/10-oversell-seed.env"
  grep -q OVERSELL_SKU_ID "$EVDIR/10-oversell-seed.env" || die K6_SEED
fi

k6_common_env=( -e BASE_URL=https://shop.example.com )
run_k6() { # $1 = 输出日志路径，其余为 k6 -e 参数
  local out=$1; shift
  docker run --rm -i --add-host shop.example.com:$DOCKER_GW "$K6_IMAGE" run \
    --insecure-skip-tls-verify - "${k6_common_env[@]}" "$@" \
    < deploy/loadtest/k6-order.js 2>&1 | tee "$out"
  return "${PIPESTATUS[0]}"
}

# ---- 11. K6_SMOKE（20 TPS × 3min，exit 必须 0）----
if should_run K6_SMOKE; then
  stage "K6_SMOKE 20 TPS × 3min 可持续吞吐"
  # R4-21：preAlloc/maxVUs 收敛到预热池（SMOKE_PRELOGIN_USERS=160，setup 阶段受控
  # 预登录+预建地址）以内——稳态 20 TPS 在飞迭代只需几十个 VU，旧 200/800 配置在尾延迟
  # 上升时不断拉起新 VU，每个新 VU 首迭代齐射 BCrypt 登录，正反馈打挂 user-service。
  if run_k6 "$EVDIR/11-k6-smoke.log" \
      -e MODE=smoke -e USER_POOL=$USER_POOL -e SKU_COUNT=$SKU_COUNT \
      -e SMOKE_RATE=20 -e SMOKE_DURATION=3m \
      -e SMOKE_PREALLOC_VUS=120 -e SMOKE_MAX_VUS=160 \
      -e SMOKE_PRELOGIN_USERS=160; then
    :
  else
    die K6_SMOKE
  fi
fi

# ---- 12. K6_OVERSELL（winners=losers=paid=stock，exit 必须 0）----
if should_run K6_OVERSELL; then
  stage "K6_OVERSELL 超卖硬断言 ${OVERSELL_STOCK} 库存 / $((OVERSELL_STOCK*2)) 并发"
  set -a; . "$EVDIR/10-oversell-seed.env"; set +a
  if run_k6 "$EVDIR/12-k6-oversell.log" \
      -e MODE=oversell -e STOCK=$OVERSELL_STOCK -e RUSH_MULT=2 \
      -e USER_POOL=100 -e CHANNEL_SECRET="$CHANNEL_SECRET" \
      -e OVERSELL_SKU_ID="$OVERSELL_SKU_ID" -e OVERSELL_ACTIVITY_ID="$OVERSELL_ACTIVITY_ID"; then
    :
  else
    die K6_OVERSELL
  fi
  # DB 三方对账
  echo "== DB 对账 =="
  docker exec -i shop-mysql mysql -uroot -proot -N -e "
    SELECT 'orders_status20', COUNT(*) FROM shop_order.t_order_order WHERE seckill_activity_id=$OVERSELL_ACTIVITY_ID AND status=20;
    SELECT 'pay_status30', COUNT(*) FROM shop_pay.t_pay_order WHERE order_no IN (SELECT order_no FROM shop_order.t_order_order WHERE seckill_activity_id=$OVERSELL_ACTIVITY_ID) AND status=30;" 2>/dev/null | tee "$EVDIR/12-db-reconcile.txt"
fi

stage "最终验收全部完成"
echo "证据目录：$EVDIR"
