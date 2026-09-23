#!/usr/bin/env bash
# =============================================================================
# 生产级高可用混沌验收脚本（本地 docker compose 中间件 + 宿主机直跑应用形态）
#
# 验证内容：
#   1. 网关健康 + 7 个业务服务在 Nacos 的注册健康（混沌前置基线）
#   2. 全程持续业务读流量（约 0.5s 一次商品列表 GET），分窗口统计失败率/延迟：
#      基线（必须 0 失败）、Redis 宕机窗口、RocketMQ 宕机窗口、恢复后稳态；
#      每次恢复后必须有连续 30s 0 失败的稳态证明
#   3. Redis 宕机 20s：故障期间读链路允许降级/失败（预期内），恢复后网关连续
#      20 次成功，且 7 个业务服务 8081..8087 直连 actuator/health 全部 UP
#      （验证 Redis 重连自愈、无进程永久崩溃）
#   3.5 POST 写链路 Redis 宕机 20s（R-B4）：登录/下单/支付请求在故障窗口内必须
#       5s 内快速返回统一包裹体（网关桶或服务层 fail-closed 拒绝/正确降级，禁止
#       无限等待与裸 500），恢复后登录、支付、下单依次自愈回业务语义（前置 seed.sh）
#   4. RocketMQ broker 宕机 45s：7 库 t_mq_outbox 允许堆积，relay 必须持续重试
#      不崩溃；broker 恢复后 7 个库在【同一个 90s deadline 内并行】全部清空
#      status=0 到期待投递行（验证 outbox at-least-once 最终一致 + 消费幂等）
#   5. V5 挂起事件「慢车道」自愈：broker 恢复后，向 7 库各注入 1 条冷却期已满的
#      status=2 挂起行（模拟故障期挂起 → 恢复 → 冷却到点），探针 topic 预建，
#      120s 内必须被慢车道扫描重排并由快车道投递（status=1），或重新挂起且
#      suspend_count 增长；严禁长期卡在初始 (status=2,suspend_count=0) 状态
#
# 前置条件：
#   - deploy/local/start-apps.sh 已启动 8 个应用（7 服务 8081..8087 + 网关 8080）
#   - deploy/docker-compose 的中间件已 up：shop-mysql / shop-redis /
#     shop-rmq-broker（proxy 宿主机端口 18081）/ nacos:8848
#
# 预期运行时长：约 4~5 分钟。EXIT trap 保证：任何失败/中断都会重新拉起
# shop-redis、shop-rmq-broker 并清理临时文件与探针数据，可重复执行。
# 多副本 pod-kill / PDB / 滚动不断流由 deploy/kubernetes/ha-check.sh 覆盖。
#
# 用法：bash deploy/loadtest/chaos.sh [BASE_URL=http://localhost:8080]
# =============================================================================
set -uo pipefail

# 脚本内含 UTF-8 中文文案。铁律：变量与多字节字符（全角逗号/括号等）相邻时
# 【必须】写 ${VAR} 花括号定界。W7 两次实证：
# 1) 后台 shell 的 LC_CTYPE=UTF-8 是 macOS 无效值，回落 C 后高字节并入变量名；
# 2) 即使 LC_ALL=en_US.UTF-8，macOS 自带 /bin/bash 3.2.57 仍会把全角字节吞进变量名
#    （/bin/bash -c 'set -u;A=1;echo "$A，x"' 实测 unbound variable，locale 无效）。
# 故下方仍强制 UTF-8 locale（帮助 sed/awk/grep 等工具），但真正的修复是花括号；
# 同类隐患已全量扫描 deploy/**/*.sh 并全部加括号（final-acceptance/apply-sql/seed/
# start-apps/10-apply-sql/create-secrets/ha-check）。
if locale -a 2>/dev/null | grep -qi '^en_US.UTF-8$'; then
  export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
else
  export LC_ALL=C.UTF-8 LANG=C.UTF-8
fi

BASE=${BASE_URL:-http://localhost:8080}
PRODUCT_PATH="/api/product/products?pageNum=1&pageSize=1"

PASS=0; FAIL=0
ok()  { echo "  [PASS] $1"; PASS=$((PASS+1)); }
bad() { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }

# 7 个业务库（与服务/端口顺序一一对应：8081..8087）
dbs=(shop_user shop_product shop_marketing shop_order shop_pay shop_settlement shop_aftersale)
svc_names=(user product marketing order pay settlement aftersale)

mysql_q() { docker exec -i shop-mysql mysql -uroot -proot -N -e "$1" 2>/dev/null; }

# -----------------------------------------------------------------------------
# 临时目录与退出兜底：永远恢复中间件、删除慢车道探针行、停流量、清临时文件
# -----------------------------------------------------------------------------
TMP=$(mktemp -d /tmp/shop-chaos.XXXXXX)
TRAFFIC_LOG="$TMP/traffic.log"
TRAFFIC_PID=""
BIZ_KEY="chaos-$(date +%s)-$$"
PROBE_ID=$((9000000000000000000 + RANDOM * 100000 + RANDOM))

cleanup() {
  local rc=$?
  if [ -n "${BIZ_KEY:-}" ] && [ "${#dbs[@]}" -gt 0 ]; then
    for db in "${dbs[@]}"; do
      docker exec -i shop-mysql mysql -uroot -proot -e \
        "DELETE FROM $db.t_mq_outbox WHERE biz_key='$BIZ_KEY';" >/dev/null 2>&1 || true
    done
  fi
  # 绝不留下停掉的中间件
  docker start shop-redis shop-rmq-broker >/dev/null 2>&1 || true
  [ -n "${TRAFFIC_PID:-}" ] && kill "$TRAFFIC_PID" 2>/dev/null || true
  [ -n "${TMP:-}" ] && rm -rf "$TMP"
  exit "$rc"
}
trap cleanup EXIT

# -----------------------------------------------------------------------------
# 后台持续流量发生器：每 ~0.5s 一个公开读请求，记录 时间戳/失败标记/耗时(秒)，
# 父脚本按时间戳切片统计各混沌窗口。curl -m 3，超时/非 200 均记失败。
# -----------------------------------------------------------------------------
traffic_loop() {
  local url="$BASE$PRODUCT_PATH" out code lat
  while :; do
    out=$(curl -s -o /dev/null -m 3 -w '%{http_code} %{time_total}' "$url" 2>/dev/null || true)
    code=${out%% *}; lat=${out##* }
    [ -n "${lat:-}" ] || lat=0
    case "$code" in
      200) printf '%s 0 %s\n' "$(date +%s)" "$lat" >> "$TRAFFIC_LOG" ;;
      *)   printf '%s 1 %s\n' "$(date +%s)" "$lat" >> "$TRAFFIC_LOG" ;;
    esac
    sleep 0.5 2>/dev/null || exit 0
  done
}

# 按 [start,end) 时间切片聚合流量日志：请求数 失败数 失败率% 最大延迟(s) p95≈(s)
win_stats() {
  awk -v s="$1" -v e="$2" '
    $1+0 >= s+0 && $1+0 < e+0 {
      n++; f += $2; sum += $3;
      if ($3+0 > mx) mx = $3+0;
      b = int($3 * 10); bucket[b]++;
    }
    END {
      if (!n) { print "0 0 0.0 0.000 0.000"; exit; }
      target = 0.95 * n; acc = 0; p95 = mx;
      for (i = 0; i <= 300; i++) {
        acc += bucket[i] + 0;
        if (acc >= target) { p95 = (i + 1) / 10; break; }
      }
      printf "%d %d %.1f %.3f %.3f\n", n, f, 100 * f / n, mx, p95;
    }' "$TRAFFIC_LOG"
}

# 7 库 status=0 且到期的 outbox 行数合计
sum_due() {
  local tot=0 n db
  for db in "${dbs[@]}"; do
    n=$(mysql_q "SELECT COUNT(*) FROM $db.t_mq_outbox WHERE status=0 AND deliver_at<=NOW(3);" || echo 0)
    tot=$((tot + ${n:-0}))
  done
  echo "$tot"
}

SEC_FAIL=0
sec() { # 小节汇要：自上次打点以来是否出现 FAIL
  local added=$((FAIL - SEC_FAIL)); SEC_FAIL=$FAIL
  if [ "$added" -eq 0 ]; then echo "  >>> 小节[$1] PASS"; else echo "  >>> 小节[$1] FAIL（新增 $added 项断言失败）"; fi
}

echo "== 1. 网关与注册中心（混沌前置检查） =="
curl -sf -m 5 "$BASE/actuator/health" >/dev/null && ok "gateway health" || bad "gateway health"
for s in "${svc_names[@]}"; do
  cnt=$(curl -s -m 5 "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=shop-${s}-service" \
        | grep -o '"healthy":true' | wc -l | tr -d ' ')
  [ "${cnt:-0}" -ge 1 ] && ok "$s registered ($cnt)" || bad "$s not registered"
done
sec "1 网关与注册中心"
[ "$FAIL" -eq 0 ] || { echo "前置检查未通过，终止混沌"; exit 1; }

# 启动全程持续流量
: > "$TRAFFIC_LOG"
traffic_loop & TRAFFIC_PID=$!
T_BASE=$(date +%s)

echo
echo "== 2. 持续业务流量基线（15s，失败率必须为 0） =="
sleep 15
read -r B_REQ B_FAIL B_RATE B_MAX B_P95 < <(win_stats "$T_BASE" "$(date +%s)")
echo "   基线流量: 请求=$B_REQ 失败=$B_FAIL 失败率=${B_RATE}% 最大延迟=${B_MAX}s p95≈${B_P95}s"
if [ "$B_FAIL" = "0" ] && [ "$B_REQ" -ge 10 ]; then
  ok "基线窗口零失败（环境健康，可以开始混沌）"
else
  bad "基线窗口已存在失败（请求=$B_REQ 失败=${B_FAIL}），拒绝在不健康环境上做混沌"
  sec "2 流量基线"
  exit 1
fi
sec "2 流量基线"

# =============================================================================
# 3. Redis 故障 20s
# =============================================================================
echo
echo "== 3. Redis 故障 20s（持续读流量不间断，故障期降级属预期） =="
T_RDOWN=$(date +%s)
docker stop shop-redis >/dev/null
echo "   $(date '+%H:%M:%S') shop-redis 已停止，持续流量继续..."
sleep 20
docker start shop-redis >/dev/null
until docker exec shop-redis redis-cli ping 2>/dev/null | grep -q PONG; do sleep 1; done
T_RREC=$(date +%s)
echo "   $(date '+%H:%M:%S') shop-redis 已恢复 PONG"

read -r R_REQ R_FAIL R_RATE R_MAX _ < <(win_stats "$T_RDOWN" "$T_RREC")
echo "   Redis 宕机窗口: 请求=$R_REQ 失败=$R_FAIL 失败率=${R_RATE}% 最大延迟=${R_MAX}s（失败属预期降级）"

# 恢复后网关侧连续 20 次成功（原阈值 8，提升到 20）
REC_OK=0
for i in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w '%{http_code}' -m 3 "$BASE$PRODUCT_PATH")
  if [ "$c" = "200" ]; then REC_OK=$((REC_OK+1)); else REC_OK=0; fi
  [ "$REC_OK" -ge 20 ] && break
  sleep 1
done
[ "$REC_OK" -ge 20 ] && ok "Redis 恢复后网关连续 20 次成功（连接池自愈）" \
  || bad "Redis 恢复后网关未恢复稳定（最长连续成功=${REC_OK}）"

# 7 个业务服务直连健康（绕开网关/Redis），全部必须 UP
for i in 0 1 2 3 4 5 6; do
  port=$((8081 + i)); name=${svc_names[$i]}
  body=$(curl -s -m 5 "http://localhost:$port/actuator/health" || true)
  echo "$body" | grep -q '"status":"UP"' \
    && ok "$name-service :$port actuator UP（无永久崩溃）" \
    || bad "$name-service :$port 健康异常: ${body:-无响应}"
done

# 恢复后稳态：连续 30s 0 失败（以持续流量日志为准）
T_RSTEADY=$(date +%s)
echo "   观察 Redis 恢复后稳态 30s..."
sleep 30
read -r RS_REQ RS_FAIL RS_RATE RS_MAX _ < <(win_stats "$T_RSTEADY" "$((T_RSTEADY + 30))")
echo "   Redis 恢复稳态窗口: 请求=$RS_REQ 失败=$RS_FAIL 失败率=${RS_RATE}%"
[ "$RS_FAIL" = "0" ] && ok "Redis 恢复后连续 30s 稳态零失败" \
  || bad "Redis 恢复后 30s 稳态仍有 $RS_FAIL/$RS_REQ 次失败"
sec "3 Redis 混沌"

# =============================================================================
# 3.5 POST 写链路 Redis 故障（R-B4）：下单/支付/登录写路径在 Redis 宕机期间必须
#     「快速失败或正确降级，禁止无限等待」，恢复后必须自愈回稳态。
#
#     分层说明：外部流量先撞网关令牌桶（ShopRedisRateLimiter fail-closed，Redis 挂
#     即在网关快速 429+10007 包裹体，这本身就是 R-B4 矩阵第 1 行的验收点）；服务层
#     @RateLimit/分布式锁/幂等切面的 fail-closed 二道防线由 shop-framework 单测覆盖。
#     本段只经网关发真实 POST，断言：
#       1) 故障窗口内每个写请求 5s 内必返回（curl -m 5；000/超时=挂死，直接 FAIL）；
#       2) 响应体必须是统一业务包裹体（含 "code" 字段），裸文本/空体一律 FAIL；
#       3) 恢复后登录重新成功、下单/支付返回业务语义码（非 10008/10009/10010 系统码）。
#     前置：deploy/loadtest/seed.sh 用户池与商品（取不到登录态则整段跳过，不计 FAIL）。
# =============================================================================
echo
echo "== 3.5 POST 写链路 Redis 故障 20s：快速失败/正确降级，恢复后自愈（R-B4） =="

WRITE_TOTAL=0; WRITE_FAST=0; WRITE_ENVELOPE=0
# $1=标签 $2=路径 $3=token(可空) $4=JSON body
write_probe() {
  local label="$1" path="$2" token="$3" body="$4" out code lat resp
  local hdr=(-H 'Content-Type: application/json')
  [ -n "$token" ] && hdr+=(-H "Authorization: Bearer $token")
  out=$(curl -s -m 5 -w $'\n%{http_code} %{time_total}' -X POST \
        "${hdr[@]}" -d "$body" "$BASE$path" 2>/dev/null || true)
  code=$(echo "$out" | tail -1 | awk '{print $1}')
  lat=$(echo "$out" | tail -1 | awk '{print $2}')
  resp=$(echo "$out" | sed '$d')
  WRITE_TOTAL=$((WRITE_TOTAL + 1))
  if [ "$code" != "000" ] && [ -n "$code" ]; then
    WRITE_FAST=$((WRITE_FAST + 1))
  else
    bad "写链路[$label] Redis 宕机期间挂死/无响应（违反快速失败，http=000 lat=${lat}s）"
    return
  fi
  if echo "$resp" | grep -q '"code"'; then
    WRITE_ENVELOPE=$((WRITE_ENVELOPE + 1))
  else
    bad "写链路[$label] 返回非业务包裹体：http=$code body=$(echo "$resp" | head -c 160)"
  fi
}

# 探针身份自助引导（幂等）：最终验收链 E2E 引导用 USER_POOL=0（不灌压测用户池），
# 而本段需要 load_1/load_50 两个买家。注册是公开端点（10005 已存在按成功），
# 手机号公式与 seed.sh 一致（139%08d），在已灌过用户池的环境也不产生重复账号。
ensure_buyer() { # $1=序号
  local n=$1 ph; ph=$(printf "139%08d" $((10#$n % 100000000)))
  curl -s -m 8 -X POST "$BASE/api/user/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"load_${n}\",\"phone\":\"$ph\",\"password\":\"Load@12345\",\"nickname\":\"load_${n}\"}" >/dev/null || true
}
buyer_login() { # $1=序号 → stdout token
  curl -s -m 5 -X POST -H 'Content-Type: application/json' \
    -d "{\"account\":\"load_$1\",\"password\":\"Load@12345\"}" "$BASE/api/user/auth/login" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}

# 宕机前取得登录态/地址/SKU（仅用于恢复后的自愈断言）；身份缺失时自助幂等引导后重试一次
ensure_buyer 1
ensure_buyer 50
WTOKEN=$(buyer_login 1)
if [ -z "$WTOKEN" ]; then
  echo "   WARN: 未取得 load_1 登录态（自助注册后仍失败），跳过写链路混沌段"
else
  WADDR=$(curl -s -m 5 -H "Authorization: Bearer $WTOKEN" \
    "$BASE/api/user/users/addresses?pageNum=1&pageSize=1" \
    | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
  # 商品列表返回的是 SPU 维度（spuId），skuId 必须经详情 /products/{spuId}/skus 取（W7 实证
  # 旧实现直接 grep 列表里的 skuId 永远为空，真实下单探针被整体跳过成假覆盖）。
  WSPU=$(curl -s -m 5 "$BASE/api/product/products?pageNum=1&pageSize=1" \
    | grep -o '"spuId":[0-9]*' | head -1 | cut -d: -f2)
  WSKU=""
  if [ -n "$WSPU" ]; then
    WSKU=$(curl -s -m 5 "$BASE/api/product/products/$WSPU/skus" \
      | grep -o '"skuId":[0-9]*' | head -1 | cut -d: -f2)
  fi
  # 地址缺失则自助新建（新引导买家无地址；缺地址会跳过真实下单自愈断言）
  if [ -z "$WADDR" ]; then
    WADDR=$(curl -s -m 5 -X POST -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $WTOKEN" \
      -d '{"receiver":"混沌探针","phone":"13900000001","province":"浙江省","city":"杭州市","district":"西湖区","detailAddress":"混沌测试路1号","tag":"其他","isDefault":1}' \
      "$BASE/api/user/users/addresses" | grep -o '"data":[0-9]*' | cut -d: -f2)
  fi
  echo "   前置数据：token=OK address=${WADDR:-缺失} sku=${WSKU:-缺失}（缺失项跳过对应写用例）"

  T_WDOWN=$(date +%s)
  docker stop shop-redis >/dev/null
  echo "   $(date '+%H:%M:%S') shop-redis 已停止，POST 写链路探针持续 20s..."
  ROUND=0
  while [ $(( $(date +%s) - T_WDOWN )) -lt 20 ]; do
    ROUND=$((ROUND + 1))
    # 登录写路径（auth:login 服务层 @RateLimit + 网关桶都依赖 Redis）
    write_probe "login" "/api/user/auth/login" "" \
      "{\"account\":\"load_$((ROUND + 10))\",\"password\":\"Load@12345\"}"
    # 下单写路径（OrderNoGenerator INCR + order:create @RateLimit 均 Redis 硬依赖）
    if [ -n "$WADDR" ] && [ -n "$WSKU" ]; then
      write_probe "order-create" "/api/order/orders" "$WTOKEN" \
        "{\"clientToken\":\"chaos-down-$(date +%s)-$ROUND-$RANDOM\",\"orderType\":1,\"source\":1,\"addressId\":$WADDR,\"items\":[{\"skuId\":$WSKU,\"qty\":1}],\"freightFen\":0}"
    fi
    # 支付写路径（DistributedLockTemplate 分布式锁为 Redis 硬依赖）；假单号仅验证快速失败，
    # 不产生真实资金数据，恢复后同请求应返回业务错误码（证明服务可达而非系统故障）
    write_probe "pay-create" "/api/pay/pays" "$WTOKEN" \
      "{\"orderNo\":\"CHAOS-DOWN-$BIZ_KEY-$ROUND\",\"payMethod\":1,\"amountFen\":1,\"subject\":\"chaos\",\"terminal\":1}"
    sleep 2
  done
  docker start shop-redis >/dev/null
  until docker exec shop-redis redis-cli ping 2>/dev/null | grep -q PONG; do sleep 1; done
  T_WREC=$(date +%s)
  echo "   $(date '+%H:%M:%S') shop-redis 已恢复 PONG（故障窗口 $((T_WREC - T_WDOWN))s，探针 $WRITE_TOTAL 次）"

  [ "$WRITE_FAST" = "$WRITE_TOTAL" ] && [ "$WRITE_TOTAL" -gt 0 ] \
    && ok "写链路 Redis 宕机期间 ${WRITE_TOTAL}/${WRITE_TOTAL} 请求在 5s 内返回（无无限等待/连接堆积）" \
    || bad "写链路存在挂死/无响应：${WRITE_FAST}/${WRITE_TOTAL} 快速返回"
  [ "$WRITE_ENVELOPE" = "$WRITE_TOTAL" ] \
    && ok "写链路故障响应全部为业务包裹体（fail-closed 拒绝/降级，无裸 500）" \
    || bad "写链路存在非包裹体响应：${WRITE_ENVELOPE}/${WRITE_TOTAL}"

  # 恢复后自愈：登录重新成功（load_50 已在段首幂等引导）。
  # redis-cli PONG 只代表进程存活，Redisson 客户端重连/令牌桶重建有数秒窗口，
  # 立即单次登录可能误判，按最多 20s 轮询（W7 实证 PONG 后首次登录仍失败）。
  RTOKEN=""
  for _ in $(seq 1 10); do
    RTOKEN=$(buyer_login 50)
    [ -n "$RTOKEN" ] && break
    sleep 2
  done
  [ -n "$RTOKEN" ] && ok "写链路恢复后登录自愈（重新签发 token）" \
    || bad "写链路恢复后登录仍失败（Redis 重连未自愈）"

  # 支付探测：恢复后假单号必须返回业务语义码（订单不存在等），而非 10008/10009/10010。
  # redis-cli PONG 只代表进程存活；应用侧 Redisson 死通道排空/重建滞后于 PONG（W7 实证
  # 恢复后仍有 WriteRedisConnectionException 窗口），最多轮询 30s 等待自愈。
  PAYCODE=""; PAYBODY=""
  for _ in $(seq 1 15); do
    PAYBODY=$(curl -s -m 5 -X POST -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $WTOKEN" \
      -d "{\"orderNo\":\"CHAOS-REC-$BIZ_KEY\",\"payMethod\":1,\"amountFen\":1,\"subject\":\"chaos\",\"terminal\":1}" \
      "$BASE/api/pay/pays")
    PAYCODE=$(echo "$PAYBODY" | sed -n 's/.*"code":\([0-9]*\).*/\1/p' | head -1)
    case "$PAYCODE" in 10008|10009|10010|"") sleep 2;; *) break;; esac
  done
  if [ -n "$PAYCODE" ] && [ "$PAYCODE" != "10008" ] && [ "$PAYCODE" != "10009" ] && [ "$PAYCODE" != "10010" ]; then
    ok "写链路恢复后支付服务可达（返回业务码 code=${PAYCODE}，非系统故障码）"
  else
    bad "写链路恢复后支付仍系统故障：$PAYBODY"
  fi

  # 真实下单自愈（地址/SKU 齐全时）：code=0 或业务拒绝（30001 库存不足等）均可，
  # 系统故障码 10008/10009/10010 视为未自愈；同样按最多 30s 轮询应用侧重连窗口。
  if [ -n "$WADDR" ] && [ -n "$WSKU" ]; then
    ORDCODE=""; ORDBODY=""
    for _ in $(seq 1 15); do
      ORDBODY=$(curl -s -m 5 -X POST -H 'Content-Type: application/json' \
        -H "Authorization: Bearer $WTOKEN" \
        -d "{\"clientToken\":\"chaos-rec-$BIZ_KEY-$RANDOM\",\"orderType\":1,\"source\":1,\"addressId\":$WADDR,\"items\":[{\"skuId\":$WSKU,\"qty\":1}],\"freightFen\":0}" \
        "$BASE/api/order/orders")
      ORDCODE=$(echo "$ORDBODY" | sed -n 's/.*"code":\([0-9]*\).*/\1/p' | head -1)
      case "$ORDCODE" in 10008|10009|10010|"") sleep 2;; *) break;; esac
    done
    if [ -n "$ORDCODE" ] && [ "$ORDCODE" != "10008" ] && [ "$ORDCODE" != "10009" ] && [ "$ORDCODE" != "10010" ]; then
      ok "写链路恢复后下单自愈（返回业务码 code=${ORDCODE}）"
    else
      bad "写链路恢复后下单仍系统故障：$ORDBODY"
    fi
  else
    echo "   WARN: 缺地址/SKU，跳过恢复后真实下单自愈断言（登录+支付路径已覆盖）"
  fi
fi
sec "3.5 POST 写链路 Redis 故障"

# =============================================================================
# 4. RocketMQ broker 故障 45s + outbox 最终一致 + V5 慢车道探针注入
# =============================================================================
echo
echo "== 4. RocketMQ broker 故障 45s → outbox 堆积 → 恢复并行清空 =="

# 4.1 慢车道前置数据采集（broker 停机前）
S0=$(mysql_q "SELECT COUNT(*) FROM shop_order.t_mq_outbox WHERE status=2;" || echo -1)
echo "   停机前 shop_order status=2 挂起存量 S0=${S0:-未知}"

col=$(mysql_q "SHOW COLUMNS FROM shop_order.t_mq_outbox LIKE 'suspend_count';" || true)
echo "$col" | grep -q suspend_count \
  && ok "V5 迁移已应用（t_mq_outbox.suspend_count 列存在）" \
  || bad "V5 迁移缺失：shop_order.t_mq_outbox 无 suspend_count 列"

# 慢车道探针在【第 5 节 broker 恢复之后】才注入：注入即代表“故障期被挂起、冷却已满”的
# 消息。若在停机前注入，慢车道会在停机窗口前把它重排到快车道，随后发送失败进入指数退避
# （2→4→…→300s），各服务退避时刻不一，观察窗内会看到“在途 status=0”的假卡住。

BEFORE=$(sum_due)
echo "   停机前 7 库 status=0 到期行合计=$BEFORE"

# 4.2 broker 停机 45s
T_MDOWN=$(date +%s)
docker stop shop-rmq-broker >/dev/null
echo "   $(date '+%H:%M:%S') shop-rmq-broker 已停止，持续读流量继续，relay 应重试不崩溃..."
sleep 45
DURING=$(sum_due)
echo "   停机 45s 时 7 库 status=0 到期行合计=${DURING}（允许堆积；无业务写流量时也可为 0）"

# 4.3 恢复并等待 proxy 端口 + 容器可执行（双重就绪）
docker start shop-rmq-broker >/dev/null
MQ_READY=0
for i in $(seq 1 60); do
  if nc -z localhost 18081 2>/dev/null \
     && docker exec shop-rmq-broker sh -c 'echo true' >/dev/null 2>&1; then
    MQ_READY=1; break
  fi
  sleep 2
done
T_MREC=$(date +%s)
[ "$MQ_READY" = "1" ] && ok "broker 恢复：localhost:18081 可连且容器就绪（用时约 $((T_MREC - T_MDOWN - 45))s）" \
  || bad "broker 恢复后 120s 内 localhost:18081/容器仍未就绪"

AFTER=$(sum_due)
echo "   broker 刚恢复时 7 库 status=0 到期行合计=${AFTER}，开始并行清空..."

# 4.4 7 库并行 drain，共享同一个 90s deadline（取代原来每库串行 90s）
DEADLINE=$((T_MREC + 90))
drain_pids=()
for db in "${dbs[@]}"; do
  (
    left="?"
    while [ "$(date +%s)" -lt "$DEADLINE" ]; do
      left=$(mysql_q "SELECT COUNT(*) FROM $db.t_mq_outbox WHERE status=0 AND deliver_at<=NOW(3);" 2>/dev/null)
      [ "${left:-}" = "0" ] && { echo "OK 0" > "$TMP/drain.$db"; exit 0; }
      sleep 2
    done
    echo "FAIL ${left:-?}" > "$TMP/drain.$db"
  ) &
  drain_pids+=("$!")
done

# 恢复后稳态：连续 30s 0 失败（与 drain 并行，不额外占用时间）
echo "   观察 MQ 恢复后稳态 30s（同时等待 drain）..."
sleep 30
read -r MS_REQ MS_FAIL MS_RATE MS_MAX _ < <(win_stats "$T_MREC" "$((T_MREC + 30))")
echo "   MQ 恢复稳态窗口: 请求=$MS_REQ 失败=$MS_FAIL 失败率=${MS_RATE}%"
[ "$MS_FAIL" = "0" ] && ok "RocketMQ 恢复后连续 30s 稳态零失败" \
  || bad "RocketMQ 恢复后 30s 稳态有 $MS_FAIL/$MS_REQ 次失败"

# 只等 drain 子进程：裸 wait 会把贯穿全程的 traffic_loop 也等上，形成死锁
# （traffic_loop 的停止信号在第 6 节汇总后才落下）
for p in "${drain_pids[@]}"; do wait "$p" 2>/dev/null || true; done
DRAIN_PASS=0
for db in "${dbs[@]}"; do
  res=$(cat "$TMP/drain.$db" 2>/dev/null || echo "FAIL ?")
  if [ "${res%% *}" = "OK" ]; then
    echo "    [PASS] $db outbox 在共享 90s 窗口内全部投递成功"; DRAIN_PASS=$((DRAIN_PASS+1))
  else
    bad "$db outbox 恢复后共享 90s 窗口内仍有到期 pending（${res#FAIL }）"
  fi
done
[ "$DRAIN_PASS" -eq 7 ] && ok "MQ 故障恢复后 7 库消息最终一致（at-least-once + 消费幂等）" \
  || bad "仅 $DRAIN_PASS/7 库在共享 90s 窗口内清空 outbox"
sec "4 RocketMQ 混沌 + outbox 清空"

# =============================================================================
# 5. V5 慢车道断言：7 库探针必须在 120s 内离开初始挂起态
#    可接受终态：status=1（已投递，topic 自动创建后 sendRaw 成功）
#               或 status=2 且 suspend_count>=1（又耗完一轮重挂，证明被扫描过）
#    不可接受：120s 后仍是 (status=2, suspend_count=0) —— 说明慢车道从未触发
# =============================================================================
echo
echo "== 5. V5 慢车道自愈断言（共享 120s deadline，7 库并行） =="
# 5.1 broker 已恢复：向 7 库注入“故障期挂起、300s 冷却已满”的探针行，验证慢车道自动重放。
# 探针事件必须投递到【真实存在】的 topic：RocketMQ 5.x gRPC proxy 默认不自动建 topic，
# 假 topic 会让快车道永远发送失败，探针根本走不到 status=1（生产同样要求 topic 预建，
# 不依赖 TBW102 自动创建）。updateTopic 幂等，已存在时仅更新路由。
PROBE_TOPIC=shop_chaos_probe
if docker exec shop-rmq-namesrv sh -c \
   '/home/rocketmq/rocketmq-5.3.1/bin/mqadmin updateTopic -n 127.0.0.1:9876 -c DefaultCluster -t '"$PROBE_TOPIC"' -r 8 -w 8' \
   >/dev/null 2>&1; then
  ok "探针 topic $PROBE_TOPIC 已在 broker 预建（幂等）"
else
  bad "探针 topic $PROBE_TOPIC 预建失败（慢车道投递将无法到达 status=1）"
fi

# 7 库各注入 1 条 status=2 挂起行：update_time 置为 310s 前，超过 300s 默认冷却，
# 下一轮慢车道扫描（每 60s）必然立即命中并重排，快车道随后对健康 broker 一次投递成功。
INJECT_OK=0
for db in "${dbs[@]}"; do
  sql="INSERT INTO $db.t_mq_outbox
       (id,topic,tag,biz_key,body_json,deliver_at,status,retry_count,suspend_count,create_time,update_time)
       VALUES ($PROBE_ID,'$PROBE_TOPIC','chaos','$BIZ_KEY','{\"probe\":true}',
        NOW(3),2,20,0,
        DATE_ADD(NOW(3),INTERVAL -310 SECOND),DATE_ADD(NOW(3),INTERVAL -310 SECOND));"
  if docker exec -i shop-mysql mysql -uroot -proot -e "$sql" >/dev/null 2>&1; then
    echo "    [PASS] $db 慢车道探针已注入 id=$PROBE_ID"; INJECT_OK=$((INJECT_OK+1))
  else
    bad "$db 慢车道探针注入失败"
  fi
done
[ "$INJECT_OK" -eq 7 ] && ok "7 库慢车道探针全部注入（biz_key=${BIZ_KEY}）" \
  || bad "慢车道探针仅 $INJECT_OK/7 库注入成功"

SDEADLINE=$(( $(date +%s) + 120 ))
slow_pids=()
for db in "${dbs[@]}"; do
  (
    while :; do
      row=$(mysql_q "SELECT CONCAT(status,',',IFNULL(suspend_count,-1)) FROM $db.t_mq_outbox WHERE biz_key='$BIZ_KEY';" 2>/dev/null)
      st=${row%%,*}; sc=${row##*,}
      if [ "${st:-}" = "1" ]; then
        echo "OK delivered(status=1)" > "$TMP/slow.$db"; exit 0
      fi
      if [ "${st:-}" = "2" ] && [ "${sc:-0}" -ge 1 ] 2>/dev/null; then
        echo "OK resuspended(suspend_count=$sc)" > "$TMP/slow.$db"; exit 0
      fi
      if [ "$(date +%s)" -ge "$SDEADLINE" ]; then
        echo "FAIL stuck(status=${st:-缺失},suspend_count=${sc:-?})" > "$TMP/slow.$db"; exit 1
      fi
      sleep 3
    done
  ) &
  slow_pids+=("$!")
done
# 只等慢车道探针子进程（绝不能用裸 wait，理由同 drain）
for p in "${slow_pids[@]}"; do wait "$p" 2>/dev/null || true; done
SLOW_PASS=0
for db in "${dbs[@]}"; do
  res=$(cat "$TMP/slow.$db" 2>/dev/null || echo "FAIL 无结果")
  if [ "${res%% *}" = "OK" ]; then
    echo "    [PASS] $db 慢车道探针已自愈：${res#OK }"; SLOW_PASS=$((SLOW_PASS+1))
  else
    bad "$db 慢车道探针 ${res#FAIL }"
  fi
done
[ "$SLOW_PASS" -eq 7 ] && ok "7 库慢车道在 120s 内全部完成自动重放（V5 自愈生效）" \
  || bad "仅 $SLOW_PASS/7 库慢车道探针完成自愈"

# 死信告警面观察（suspend_count>=3 仅打印，不作为失败项）
S1=$(mysql_q "SELECT COUNT(*) FROM shop_order.t_mq_outbox WHERE status=2;" || echo -1)
DEAD=$(mysql_q "SELECT COUNT(*) FROM shop_order.t_mq_outbox WHERE status=2 AND suspend_count>=3;" || echo -1)
echo "   观察项（不参与判定）：shop_order 挂起存量 S0=${S0:-?} → S1=${S1:-?}；其中 suspend_count>=3 死信=${DEAD:-?}（由日志 ERROR 告警转人工/对账）"
sec "5 V5 慢车道"

# =============================================================================
# 6. 汇总：分窗口失败率表 + 全程流量指标
# =============================================================================
echo
echo "== 6. 混沌结果汇总 =="
T_END=$(date +%s)
read -r T_REQ T_FAIL T_RATE T_MAX T_P95 < <(win_stats "$T_BASE" "$T_END")

# 100ms 分桶直方图（仅展示非空桶）
echo "   延迟分桶(100ms):"
awk '{ b=int($3*10); bucket[b]++ }
     END { for (i=0; i<=300; i++) if (bucket[i]) printf "      %.1f-%.1fs: %d\n", i/10, (i+1)/10, bucket[i] }' \
     "$TRAFFIC_LOG"

printf "   %-28s %6s %6s %8s %10s\n" "窗口" "请求" "失败" "失败率" "最大延迟"
print_row() {
  local label=$1 s=$2 e=$3
  read -r r f rate mx _ < <(win_stats "$s" "$e")
  printf "   %-28s %6s %6s %7s%% %9ss\n" "$label" "$r" "$f" "$rate" "$mx"
}
print_row "基线（混沌前 15s）"        "$T_BASE"    "$T_RDOWN"
print_row "Redis 宕机窗口（预期降级）" "$T_RDOWN"   "$T_RREC"
[ -n "${T_WDOWN:-}" ] && print_row "写链路 Redis 宕机窗口（POST 探针）" "$T_WDOWN" "$T_WREC"
print_row "Redis 恢复后稳态 30s"      "$T_RSTEADY" "$((T_RSTEADY + 30))"
print_row "RocketMQ 宕机窗口"         "$T_MDOWN"   "$T_MREC"
print_row "RocketMQ 恢复后稳态 30s"   "$T_MREC"    "$((T_MREC + 30))"

echo
echo "持续流量: 请求=$T_REQ 失败=$T_FAIL 失败率=${T_RATE}% 最大延迟=${T_MAX}s（全程 p95≈${T_P95}s）"
echo "混沌结果: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
