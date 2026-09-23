#!/usr/bin/env bash
# 一键构建并启动全部微服务（本地形态：7 服务 + 网关，直连 deploy/docker-compose 的中间件）。
# 高可用形态（多副本/PDB/HPA）见 deploy/kubernetes。
#
# 用法：
#   ./deploy/local/start-apps.sh           # 构建并后台启动
#   ./deploy/local/start-apps.sh --skip-build
#   ./deploy/local/stop-apps.sh            # 停止
#
# 日志：deploy/local/logs/<service>.log；PID：deploy/local/pids/<service>.pid
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
set +u
source ~/.sdkman/bin/sdkman-init.sh
set -u

SERVICES=(shop-user-service shop-product-service shop-marketing-service \
          shop-order-service shop-pay-service shop-settlement-service shop-aftersale-service)
PORTS=(8081 8082 8083 8084 8085 8086 8087)
LOGDIR=$ROOT/deploy/local/logs
PIDDIR=$ROOT/deploy/local/pids
mkdir -p "$LOGDIR" "$PIDDIR"

# RocketMQ proxy 在宿主机映射为 18081（避让 user-service 8081）；Spring relaxed binding → shop.mq.endpoints
export SHOP_MQ_ENDPOINTS=localhost:18081

# 安全密钥（本地单机开发固定默认值；生产必须注入强随机值，prod profile 检测到默认值会 fail-fast）：
# SHOP_JWT_SECRET：网关验签/用户服务签发 JWT 的 HMAC 密钥
# SHOP_INTERNAL_TOKEN：服务间 Feign 调用 X-Internal-Token，/inner/** 端点据此放行
export SHOP_JWT_SECRET=dev-local-only-jwt-secret-key-0123456789abcdef
export SHOP_INTERNAL_TOKEN=dev-local-only-internal-token

# 本地形态（E2E/压测同机单 NAT 出口）放宽匿名入口限流，避免测试账号批量注册被误限；
# 生产部署不注入这两个变量，服务端使用注解严格默认（注册 5 次/min、登录 20 次/min 每 IP）。
export SHOP_RATELIMIT_AUTH_REGISTER_PERMITS=1000
export SHOP_RATELIMIT_AUTH_LOGIN_PERMITS=1000

# 本地形态全部进程同机、按端口区分；固定 Nacos 注册 IP 为回环，避免 Spring InetUtils
# 选中 Tailscale/UTM 等临时网卡地址（网卡消失后网关 lb 长连超时、全链路 503/10008）。
export SPRING_CLOUD_NACOS_DISCOVERY_IP=127.0.0.1

if [ "${1:-}" != "--skip-build" ]; then
  echo "== 全量构建 =="
  mvn -q -DskipTests clean package || { echo "构建失败"; exit 1; }
fi

# 幂等重启：若上一批 JVM 仍在跑（pidfile 追踪的 wrapper），先优雅停止再启动，
# 保证「最终 jar → 运行进程」严格一致（W7：曾因旧 jar 驻留导致新修复不生效）。
if ls "$PIDDIR"/*.pid >/dev/null 2>&1; then
  echo "== 停止已在运行的旧实例 =="
  for f in "$PIDDIR"/*.pid; do
    [ -e "$f" ] || continue
    opid=$(cat "$f" 2>/dev/null)
    if [ -n "$opid" ] && kill -0 "$opid" 2>/dev/null; then
      kill -TERM "$opid" 2>/dev/null && echo "  stopped $(basename "$f" .pid) ($opid)"
    fi
  done
  for _ in $(seq 1 15); do
    alive=0
    for f in "$PIDDIR"/*.pid; do
      [ -e "$f" ] || continue
      opid=$(cat "$f" 2>/dev/null)
      [ -n "$opid" ] && kill -0 "$opid" 2>/dev/null && alive=1
    done
    [ "$alive" = 0 ] && break
    sleep 2
  done
  # 优雅停机超时的 JVM（W7 实证 shutdown hook 可能被损坏的 NIO 通道卡死）强杀
  for svcj in shop-user-service shop-product-service shop-marketing-service \
             shop-order-service shop-pay-service shop-settlement-service shop-aftersale-service shop-gateway; do
    jpid=$(pgrep -f "$svcj/target/$svcj.jar" || true)
    if [ -n "$jpid" ]; then
      echo "  优雅停机超时，KILL $svcj ($jpid)"
      kill -KILL $jpid 2>/dev/null || true
    fi
  done
  rm -f "$PIDDIR"/*.pid
  sleep 2
fi

# RocketMQ 5.x 客户端启动时需要与 proxy 完成 gRPC telemetry 设置同步（客户端内置 3s 超时，
# 经 Docker Desktop localhost 端口转发偶发瞬时超时）。实测：7 个服务同时启动会在单一 proxy
# 上形成 ~26 个客户端的并发信道风暴，失败构造还会在 broker 侧残留短暂僵尸会话，导致部分
# consumer group 长时间反复注册失败。本地单机形态因此「逐个启动 + 等待 MQ 收敛」，
# 既验证就绪语义也规避信道风暴；生产 k8s 形态多 pod 分散在不同节点连真实 broker 集群，
# 消费者注册本身也是守护线程异步重试（见 MqConsumerRegistrar），不依赖此串行化。
STAGGER_DEADLINE=${STAGGER_DEADLINE:-300}

wait_health() {
  local svc=$1 port=$2 deadline=$3 n
  for n in $(seq 1 "$deadline"); do
    if curl -sf "http://[::1]:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "  [UP] $svc (:$port)（${n}0s 内就绪，含 MQ 消费者收敛）"; return 0
    fi
    # JVM 进程在就绪前退出（被 SIGTERM/SIGKILL、OOM、启动异常）立即结束等待，避免空转 50 分钟
    if ! kill -0 "$(cat "$PIDDIR/$svc.pid" 2>/dev/null)" 2>/dev/null; then
      echo "  [DIED] $svc JVM 已退出，退出痕迹见 $LOGDIR/$svc.log 末尾"; return 1
    fi
    sleep 10
  done
  echo "  [TIMEOUT] $svc 在 ${deadline}0s 内未完全就绪，见 $LOGDIR/$svc.log（注册仍在后台继续）"
  return 1
}

# 以可追踪方式拉起 JVM：记录退出码/致死信号（143=SIGTERM、137=SIGKILL/OOM），
# 宿主形态进程被外部回收时日志不再无痕迹；wrapper 负责把信号转发给 JVM，
# stop-apps 对 pidfile 里的 wrapper pid 发 TERM 即可优雅停机。
start_jvm() {
  local svc=$1 jar=$2 logf=$3; shift 3
  nohup bash -c '
    svc=$1; jar=$2; logf=$3; shift 3
    trap "kill -TERM $j 2>/dev/null" TERM
    trap "kill -INT  $j 2>/dev/null" INT
    java "$@" -jar "$jar" >> "$logf" 2>&1 &
    j=$!
    wait $j
    code=$?
    if [ "$code" -gt 128 ]; then
      echo "[JVM_EXIT] $svc 被信号 $(kill -l $((code-128))) 杀死（exit=${code}），时间 $(date +%H:%M:%S)" >> "$logf"
    else
      echo "[JVM_EXIT] $svc 退出码 ${code}，时间 $(date +%H:%M:%S)" >> "$logf"
    fi
    exit $code
  ' _ "$svc" "$jar" "$logf" "$@" >/dev/null 2>&1 &
}

echo "== 逐个启动 7 个服务（等待健康检查含 MQ 收敛后再启动下一个） =="
for i in "${!SERVICES[@]}"; do
  svc=${SERVICES[$i]}; port=${PORTS[$i]}
  jar=$(find "$svc/target" -maxdepth 1 -name '*.jar' ! -name '*original*' | head -1)
  if [ -z "$jar" ]; then echo "缺少 $svc jar，先执行构建"; exit 1; fi
  start_jvm "$svc" "$jar" "$LOGDIR/$svc.log" -Xms128m -Xmx512m
  echo $! > "$PIDDIR/$svc.pid"
  echo "  ${svc} pid=$! port=${port}，等待就绪..."
  wait_health "$svc" "$port" "$STAGGER_DEADLINE" || true
done

echo "== 启动网关 =="
GATEWAY_PORT=${SHOP_GATEWAY_PORT:-8080}
jar=$(find shop-gateway/target -maxdepth 1 -name '*.jar' ! -name '*original*' | head -1)
start_jvm shop-gateway "$jar" "$LOGDIR/shop-gateway.log"
echo $! > "$PIDDIR/shop-gateway.pid"
echo "  shop-gateway pid=$! port=${GATEWAY_PORT}，等待就绪..."
wait_health shop-gateway "$GATEWAY_PORT" 60 || true

echo "== 最终健康巡检 =="
ALL=("${SERVICES[@]}" shop-gateway)
ALLPORTS=("${PORTS[@]}" "$GATEWAY_PORT")
for i in "${!ALL[@]}"; do
  svc=${ALL[$i]}; port=${ALLPORTS[$i]}
  if curl -sf "http://[::1]:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    echo "  [UP] $svc (:$port)"
  else
    echo "  [DOWN] $svc (:$port) —— 见 $LOGDIR/$svc.log"
  fi
done

echo "== Nacos 注册情况 =="
for s in user product marketing order pay settlement aftersale; do
  cnt=$(curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=shop-${s}-service" \
        | grep -o '"healthy":true' | wc -l | tr -d ' ')
  echo "  shop-${s}-service healthy=$cnt"
done
echo "完成。网关入口 http://localhost:${GATEWAY_PORT}"
