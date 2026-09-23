#!/usr/bin/env bash
# 压测数据种子：引导平台账号 → 开通商户并入驻 → 类目/品牌主数据 → 批量注册消费者
#               → 商户建 SPU/SKU → 提交审核 → 平台审核通过上架（全部走网关真实 HTTP）。
# 用法： USER_POOL=10000 SKU_COUNT=1000 ./deploy/loadtest/seed.sh
#
# 幂等：重复执行安全——类目/品牌按名复用，SKU 编码 LOAD-n 唯一（重复创建被服务端拒绝后跳过），
#       已审核上架的商品不会重复审核。
set -uo pipefail
# curl 影子函数：所有 17 个调用点统一透传 CURL_EXTRA。
# kind Ingress 验收：CURL_EXTRA="-k --resolve shop.example.com:443:127.0.0.1" BASE_URL=https://shop.example.com
CURL_EXTRA=${CURL_EXTRA:-}
curl() { command curl $CURL_EXTRA "$@"; }
BASE=${BASE_URL:-http://localhost:8080}
USER_POOL=${USER_POOL:-10000}
SKU_COUNT=${SKU_COUNT:-1000}
MERCHANT_USER=${MERCHANT_USER:-load_merchant}
MERCHANT_PWD=${MERCHANT_PWD:-Load@12345}
MERCHANT_PHONE=${MERCHANT_PHONE:-13900000002}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PWD=${ADMIN_PWD:-Admin@12345}
ADMIN_PHONE=${ADMIN_PHONE:-13900000001}
BOOTSTRAP_TOKEN=${BOOTSTRAP_TOKEN:-dev-local-only-bootstrap-token}
CAT1_NAME=${CAT1_NAME:-压测一级类目}
CAT2_NAME=${CAT2_NAME:-压测二级类目}
CAT3_NAME=${CAT3_NAME:-压测三级类目}
BRAND_NAME=${BRAND_NAME:-压测品牌}
WORKDIR=${WORKDIR:-/tmp/shop-seed}
mkdir -p "$WORKDIR"

log() { echo "[$(date +%H:%M:%S)] $*"; }
need_jq() { command -v jq >/dev/null 2>&1 || { log "FATAL: 需要 jq（brew install jq）"; exit 1; }; }
need_jq

# 有界并发池（不依赖 GNU xargs -P，兼容 macOS BSD xargs 与大体积环境变量）：
# run_pool <concurrency> <worker-shell-fn-name>，worker 名经环境变量 ITEM 取任务参数
run_pool() {
  local concurrency=$1; local fn=$2
  local running=0
  while read -r ITEM; do
    export ITEM
    "$fn" &
    running=$((running + 1))
    if [ "$running" -ge "$concurrency" ]; then
      wait -n 2>/dev/null || wait
      running=$((running - 1))
    fi
  done
  wait
}

# 从 Result{code,data,...} 中取 data（原始字符串），失败返回空
data_of() { jq -r '.data // empty' 2>/dev/null; }

#######################################
# 1. 平台引导 + 商户开通/入驻
#######################################
log "引导平台账号 ${ADMIN_USER}（已存在则返回非 0，忽略）"
curl -sf -X POST "$BASE/api/user/auth/bootstrap-admin" \
  -H 'Content-Type: application/json' -H "X-Bootstrap-Token: $BOOTSTRAP_TOKEN" \
  -d "{\"username\":\"$ADMIN_USER\",\"phone\":\"$ADMIN_PHONE\",\"password\":\"$ADMIN_PWD\",\"nickname\":\"平台管理员\"}" \
  >/dev/null 2>&1 \
  && log "平台账号已引导" || log "平台引导返回非 0（可能已存在），继续"

log "平台登录"
ATOKEN=$(curl -sf -X POST "$BASE/api/user/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"account\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PWD\"}" | jq -r '.data.token // empty')
[ -n "${ATOKEN:-}" ] || { log "FATAL: 平台登录失败"; exit 1; }

# 开通商户（已存在时 data 为空，从登录链路继续）
MRESP=$(curl -s -X POST "$BASE/api/user/users/admin/accounts/merchant" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $ATOKEN" \
  -d "{\"username\":\"$MERCHANT_USER\",\"phone\":\"$MERCHANT_PHONE\",\"password\":\"$MERCHANT_PWD\",\"nickname\":\"压测商户\"}")
MID=$(echo "$MRESP" | data_of)
if [ -n "$MID" ]; then
  log "商户账号已开通 merchantId=${MID}，清算域入驻"
  curl -sf -X POST "$BASE/api/settlement/admin/merchants" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $ATOKEN" \
    -d "{\"merchantId\":$MID,\"merchantName\":\"压测商户\",\"categoryId\":1,\"categoryName\":\"默认类目\",\"commissionRateBps\":500,\"depositRequiredFen\":100000}" \
    >/dev/null 2>&1 \
    && log "商户入驻完成" || log "商户入驻返回非 0（可能已入驻），继续"
else
  log "商户开通返回非 0（可能已存在），merchantId 待登录后从上下文取"
fi

log "商户登录"
TOKEN=$(curl -sf -X POST "$BASE/api/user/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"account\":\"$MERCHANT_USER\",\"password\":\"$MERCHANT_PWD\"}" | jq -r '.data.token // empty')
[ -n "${TOKEN:-}" ] || { log "FATAL: 商户登录失败"; exit 1; }

# merchantId 兜底：本系统一店一商户，shopId 直接用 merchantId
if [ -z "${MID:-}" ]; then
  ME=$(curl -sf "$BASE/api/user/users/me" -H "Authorization: Bearer $TOKEN")
  MID=$(echo "$ME" | jq -r '.data.merchantId // .data.userId // empty')
fi
[ -n "${MID:-}" ] || { log "FATAL: 无法确定 merchantId"; exit 1; }
log "使用 merchantId=shopId=$MID"

#######################################
# 2. 批量注册压测消费者（并发 8，手机号 139 + 8 位序号）
#######################################
log "批量注册 $USER_POOL 个压测用户（并发 8）"
REG_FAIL="$WORKDIR/register-failed.txt"; : > "$REG_FAIL"
register_user() {
  local n=$ITEM
  local ph; ph=$(printf "139%08d" $((10#$n % 100000000)))
  local body code try
  # 重试矩阵：10007（注册限流，perf 环境可经 SHOP_RATELIMIT_AUTH_REGISTER_PERMITS 放宽）
  # 线性等待最多 6 轮；10005 手机号/用户名已存在按幂等成功；其它错误记录并最终失败。
  # 绝不能再 `|| true` 静默吞掉——曾发生限流把 2000 用户池静默灌成 206 个、压测账户
  # 实际规模与 USER_POOL 不符的事故。
  for try in 1 2 3 4 5 6; do
    body=$(curl -sk --max-time 10 -X POST "$BASE/api/user/auth/register" \
      -H 'Content-Type: application/json' \
      -d "{\"username\":\"load_${n}\",\"phone\":\"$ph\",\"password\":\"Load@12345\",\"nickname\":\"load_${n}\"}" 2>/dev/null)
    code=$(echo "$body" | jq -r '.code // -1' 2>/dev/null)
    [ "$code" = "0" ] && return 0
    [ "$code" = "10005" ] && return 0
    if [ "$code" = "10007" ]; then sleep $((try*2)); continue; fi
    sleep 1
  done
  echo "$n" >> "$REG_FAIL"
}
seq 1 "$USER_POOL" | run_pool 8 register_user
REG_FAIL_CNT=$(wc -l < "$REG_FAIL" | tr -d ' ')
if [ "$REG_FAIL_CNT" != "0" ]; then
  log "FATAL: $REG_FAIL_CNT/$USER_POOL 个用户注册失败（见 ${REG_FAIL}）；常见原因：注册限流未放宽"
  exit 1
fi
log "用户注册完成（$USER_POOL 个，幂等已存在计入成功）"

#######################################
# 3. 类目/品牌主数据（平台身份，按名幂等复用）
#######################################
TREE=$(curl -sf "$BASE/api/product/categories/tree")
CAT1=$(echo "$TREE" | jq -r --arg n "$CAT1_NAME" '.data[]? | select(.name==$n) | .id' | head -1)
if [ -z "$CAT1" ]; then
  CAT1=$(curl -sf -X POST "$BASE/api/product/categories" -H "Authorization: Bearer $ATOKEN" \
    -H 'Content-Type: application/json' -d "{\"name\":\"$CAT1_NAME\",\"sort\":100,\"status\":1}" | data_of)
  log "创建一级类目 id=$CAT1"
fi
CAT2=$(echo "$TREE" | jq -r --arg n "$CAT2_NAME" --arg p "$CAT1" \
  '.data[]? | select(.id==($p|tonumber)) | .children[]? | select(.name==$n) | .id' | head -1)
if [ -z "$CAT2" ]; then
  CAT2=$(curl -sf -X POST "$BASE/api/product/categories" -H "Authorization: Bearer $ATOKEN" \
    -H 'Content-Type: application/json' -d "{\"pid\":$CAT1,\"name\":\"$CAT2_NAME\",\"sort\":100,\"status\":1}" | data_of)
  log "创建二级类目 id=$CAT2"
fi
# 树缓存里二级新建后三级必不存在，直接查库式列表（再拉一次树拿最新）
TREE=$(curl -sf "$BASE/api/product/categories/tree")
CAT3=$(echo "$TREE" | jq -r --arg n "$CAT3_NAME" --arg p "$CAT2" \
  '.data[]? | .children[]? | select(.id==($p|tonumber)) | .children[]? | select(.name==$n) | .id' | head -1)
if [ -z "$CAT3" ]; then
  CAT3=$(curl -sf -X POST "$BASE/api/product/categories" -H "Authorization: Bearer $ATOKEN" \
    -H 'Content-Type: application/json' -d "{\"pid\":$CAT2,\"name\":\"$CAT3_NAME\",\"sort\":100,\"status\":1}" | data_of)
  log "创建三级类目 id=$CAT3"
fi
log "类目链：$CAT1 / $CAT2 / $CAT3"

BRAND_NAME_ENC=$(jq -nr --arg n "$BRAND_NAME" '$n|@uri')
BRAND=$(curl -sf "$BASE/api/product/brands?keyword=$BRAND_NAME_ENC&pageNum=1&pageSize=20" \
  | jq -r --arg n "$BRAND_NAME" '.data.list[]? | select(.name==$n) | .id' | head -1)
if [ -z "$BRAND" ]; then
  BRAND=$(curl -sf -X POST "$BASE/api/product/brands" -H "Authorization: Bearer $ATOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$BRAND_NAME\",\"initial\":\"Y\",\"sort\":100,\"status\":1}" | data_of)
  log "创建品牌 id=$BRAND"
fi
log "品牌：$BRAND"

#######################################
# 4. 商户建 SPU/SKU → 提交审核（并发 4）；spuId 落盘待平台批量审核
#######################################
IDS="$WORKDIR/pending-spu-ids.txt"
: > "$IDS"
log "创建 $SKU_COUNT 个压测 SPU/SKU 并提交审核"
create_submit_spu() {
  local n=$ITEM
  local RESP SPU
  RESP=$(curl -s -X POST "$BASE/api/product/merchant/products" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d "{
      \"shopId\":$MID,
      \"name\":\"压测商品${n}\",
      \"brandId\":$BRAND,
      \"category3Id\":$CAT3,
      \"mainImage\":\"https://example.invalid/seed/${n}.png\",
      \"skus\":[{
        \"skuCode\":\"LOAD-${n}\",
        \"skuName\":\"压测商品${n}-默认款\",
        \"specText\":\"规格:默认\",
        \"marketPriceFen\":12900,
        \"salePriceFen\":9900,
        \"costPriceFen\":6000,
        \"stockQty\":100000,
        \"warnThreshold\":10,
        \"stockType\":1
      }]
    }")
  SPU=$(echo "$RESP" | jq -r ".data // empty")
  [ -n "$SPU" ] || exit 0
  curl -sf -X POST "$BASE/api/product/merchant/products/$SPU/submit" \
    -H "Authorization: Bearer $TOKEN" >/dev/null 2>&1 \
    && echo "$SPU" >> "$IDS" || true
}
seq 1 "$SKU_COUNT" | run_pool 4 create_submit_spu
PENDING=$(wc -l < "$IDS" | tr -d ' ')
log "新建并提交审核 SPU=$PENDING 个（重复执行时已存在的 LOAD-n 会跳过）"

#######################################
# 5. 平台批量审核通过（直接转已上架 ON_SALE，状态机 1→3）
#######################################
if [ "$PENDING" -gt 0 ]; then
  log "平台批量审核通过（并发 4）"
  audit_spu() {
    curl -sf -X POST "$BASE/api/product/admin/products/$ITEM/audit" \
      -H "Content-Type: application/json" -H "Authorization: Bearer $ATOKEN" \
      -d "{\"pass\":true,\"remark\":\"压测种子自动审核通过\"}" >/dev/null 2>&1 || true
  }
  run_pool 4 audit_spu < "$IDS"
fi

log "种子数据准备完毕：用户池=$USER_POOL SKU=$SKU_COUNT 类目=$CAT3 品牌=$BRAND 商户=$MID"
