#!/usr/bin/env bash
# 超卖验证专用种子：复用 seed.sh 已就绪的用户池/商户/类目/品牌，
#               建一个“大库存 SPU/SKU + 小库存秒杀活动”——秒杀库存才是闸门。
#
# 前置：先执行 deploy/loadtest/seed.sh（商户 load_merchant、类目 压测三级类目、
#       品牌 压测品牌、用户 load_1..load_N 均由它创建；要求 USER_POOL >= 2*STOCK）。
#
# 用法：
#   source <(STOCK=20 bash deploy/loadtest/seed-oversell.sh)
#   # stdout 仅输出两行 eval：OVERSELL_SKU_ID=.. / OVERSELL_ACTIVITY_ID=..
#   # 同样落盘 /tmp/shop-seed/oversell.env；日志全部走 stderr。
#
# 幂等：每次运行用 epoch 生成唯一商品名/活动名/SKU 编码，互不冲突，可重复执行。
set -uo pipefail
# curl 影子函数：所有调用点统一透传 CURL_EXTRA。
# kind Ingress 验收：CURL_EXTRA="-k --resolve shop.example.com:443:127.0.0.1" BASE_URL=https://shop.example.com
CURL_EXTRA=${CURL_EXTRA:-}
curl() { command curl $CURL_EXTRA "$@"; }
BASE=${BASE_URL:-http://localhost:8080}
STOCK=${STOCK:-20}
MERCHANT_USER=${MERCHANT_USER:-load_merchant}
MERCHANT_PWD=${MERCHANT_PWD:-Load@12345}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PWD=${ADMIN_PWD:-Admin@12345}
ADMIN_PHONE=${ADMIN_PHONE:-13900000001}
BOOTSTRAP_TOKEN=${BOOTSTRAP_TOKEN:-dev-local-only-bootstrap-token}
CAT1_NAME=${CAT1_NAME:-压测一级类目}
CAT2_NAME=${CAT2_NAME:-压测二级类目}
CAT3_NAME=${CAT3_NAME:-压测三级类目}
BRAND_NAME=${BRAND_NAME:-压测品牌}
WORKDIR=${WORKDIR:-/tmp/shop-seed}
SECKILL_PRICE_FEN=${SECKILL_PRICE_FEN:-9900}
mkdir -p "$WORKDIR"

# stdout 只允许最终两行 eval，所有日志走 stderr
log() { echo "[$(date +%H:%M:%S)] $*" >&2; }
need_jq() { command -v jq >/dev/null 2>&1 || { log "FATAL: 需要 jq（brew install jq）"; exit 1; }; }
need_jq

# 从 Result{code,data,...} 中取 data（原始字符串），失败返回空
data_of() { jq -r '.data // empty' 2>/dev/null; }

# 兼容 macOS BSD date 与 Linux GNU date
if date -v-2M +%H:%M:%S >/dev/null 2>&1; then
  TS_START=$(date -v-2M '+%Y-%m-%d %H:%M:%S')
  TS_END=$(date -v+1d '+%Y-%m-%d %H:%M:%S')
else
  TS_START=$(date -d '2 minutes ago' '+%Y-%m-%d %H:%M:%S')
  TS_END=$(date -d '1 day' '+%Y-%m-%d %H:%M:%S')
fi
EPOCH=$(date +%s)
SKU_STOCK=$((STOCK * 10))
log "超卖种子开始：秒杀库存=$STOCK SPU 物理库存=$SKU_STOCK 活动窗口 $TS_START ~ $TS_END"

#######################################
# 1. 平台登录（引导失败可忽略，seed.sh 通常已建）
#######################################
curl -sf -X POST "$BASE/api/user/auth/bootstrap-admin" \
  -H 'Content-Type: application/json' -H "X-Bootstrap-Token: $BOOTSTRAP_TOKEN" \
  -d "{\"username\":\"$ADMIN_USER\",\"phone\":\"$ADMIN_PHONE\",\"password\":\"$ADMIN_PWD\",\"nickname\":\"平台管理员\"}" \
  >/dev/null 2>&1 \
  && log "平台账号已引导" || log "平台引导返回非 0（可能已存在），继续"

ATOKEN=$(curl -sf -X POST "$BASE/api/user/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"account\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PWD\"}" | jq -r '.data.token // empty')
[ -n "${ATOKEN:-}" ] || { log "FATAL: 平台登录失败（${ADMIN_USER}）"; exit 1; }

#######################################
# 2. 商户登录 + /users/me 链取 merchantId（必须先跑 seed.sh）
#######################################
MTOKEN=$(curl -sf -X POST "$BASE/api/user/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"account\":\"$MERCHANT_USER\",\"password\":\"$MERCHANT_PWD\"}" \
  | jq -r '.data.token // empty')
[ -n "${MTOKEN:-}" ] || {
  log "FATAL: 商户 $MERCHANT_USER 登录失败，请先执行 deploy/loadtest/seed.sh 完成商户开通与入驻"
  exit 1
}
ME=$(curl -sf "$BASE/api/user/users/me" -H "Authorization: Bearer $MTOKEN") || {
  log "FATAL: /api/user/users/me 链路失败，请先执行 deploy/loadtest/seed.sh"
  exit 1
}
MID=$(echo "$ME" | jq -r '.data.merchantId // .data.userId // empty')
[ -n "${MID:-}" ] || {
  log "FATAL: 无法从 /users/me 确定 merchantId，请确认 seed.sh 已完成商户入驻"
  exit 1
}
log "使用商户 merchantId=shopId=$MID"

#######################################
# 3. 复用（缺失则补建）类目链与品牌
#######################################
TREE=$(curl -sf "$BASE/api/product/categories/tree")
CAT1=$(echo "$TREE" | jq -r --arg n "$CAT1_NAME" '.data[]? | select(.name==$n) | .id' | head -1)
if [ -z "$CAT1" ]; then
  CAT1=$(curl -sf -X POST "$BASE/api/product/categories" \
    -H "Authorization: Bearer $ATOKEN" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$CAT1_NAME\",\"sort\":100,\"status\":1}" | data_of)
  log "创建一级类目 id=$CAT1"
fi
CAT2=$(echo "$TREE" | jq -r --arg n "$CAT2_NAME" --arg p "$CAT1" \
  '.data[]? | select(.id==($p|tonumber)) | .children[]? | select(.name==$n) | .id' | head -1)
if [ -z "$CAT2" ]; then
  CAT2=$(curl -sf -X POST "$BASE/api/product/categories" \
    -H "Authorization: Bearer $ATOKEN" -H 'Content-Type: application/json' \
    -d "{\"pid\":$CAT1,\"name\":\"$CAT2_NAME\",\"sort\":100,\"status\":1}" | data_of)
  log "创建二级类目 id=$CAT2"
fi
TREE=$(curl -sf "$BASE/api/product/categories/tree")
CAT3=$(echo "$TREE" | jq -r --arg n "$CAT3_NAME" --arg p "$CAT2" \
  '.data[]? | .children[]? | select(.id==($p|tonumber)) | .children[]? | select(.name==$n) | .id' | head -1)
if [ -z "$CAT3" ]; then
  CAT3=$(curl -sf -X POST "$BASE/api/product/categories" \
    -H "Authorization: Bearer $ATOKEN" -H 'Content-Type: application/json' \
    -d "{\"pid\":$CAT2,\"name\":\"$CAT3_NAME\",\"sort\":100,\"status\":1}" | data_of)
  log "创建三级类目 id=$CAT3"
fi
log "类目链：$CAT1 / $CAT2 / $CAT3"

BRAND_NAME_ENC=$(jq -nr --arg n "$BRAND_NAME" '$n|@uri')
BRAND=$(curl -sf "$BASE/api/product/brands?keyword=$BRAND_NAME_ENC&pageNum=1&pageSize=20" \
  | jq -r --arg n "$BRAND_NAME" '.data.list[]? | select(.name==$n) | .id' | head -1)
if [ -z "$BRAND" ]; then
  BRAND=$(curl -sf -X POST "$BASE/api/product/brands" \
    -H "Authorization: Bearer $ATOKEN" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$BRAND_NAME\",\"initial\":\"Y\",\"sort\":100,\"status\":1}" | data_of)
  log "创建品牌 id=$BRAND"
fi
log "品牌：$BRAND"

#######################################
# 4. 商户建唯一 SPU/SKU（物理库存 = 10*STOCK，闸门是秒杀库存）
#######################################
SPU_NAME="超卖验证商品-$EPOCH"
SKU_CODE="OVERSELL-$EPOCH"
CREATE_BODY=$(jq -n \
  --argjson shopId "$MID" \
  --arg name "$SPU_NAME" \
  --argjson brandId "$BRAND" \
  --argjson category3Id "$CAT3" \
  --arg epoch "$EPOCH" \
  --arg skuCode "$SKU_CODE" \
  --argjson stockQty "$SKU_STOCK" \
  --argjson price "$SECKILL_PRICE_FEN" \
  '{
    shopId:$shopId, name:$name, brandId:$brandId, category3Id:$category3Id,
    mainImage:("https://example.invalid/oversell/" + $epoch + ".png"),
    skus:[{
      skuCode:$skuCode, skuName:($name + "-默认款"), specText:"规格:默认",
      marketPriceFen:12900, salePriceFen:$price, costPriceFen:6000,
      stockQty:$stockQty, warnThreshold:10, stockType:1
    }]
  }')
# data 可能是裸 spuId（旧形态）或对象 {"spuId":...,"warnings":[...]}（现形态），
# 用 jq 按类型归一，否则整个 JSON 被当作 id 拼进提交审核 URL（W7 KIND K6_SEED 实证）。
SPU=$(curl -sf -X POST "$BASE/api/product/merchant/products" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $MTOKEN" \
  -d "$CREATE_BODY" | jq -r '.data | if type=="object" then (.spuId // empty) else . end // empty')
[ -n "${SPU:-}" ] || { log "FATAL: 创建超卖 SPU 失败"; exit 1; }
log "已建 SPU id=$SPU 名称=$SPU_NAME"

curl -sf -X POST "$BASE/api/product/merchant/products/$SPU/submit" \
  -H "Authorization: Bearer $MTOKEN" >/dev/null \
  || { log "FATAL: SPU $SPU 提交审核失败"; exit 1; }
log "SPU 已提交审核"

curl -sf -X POST "$BASE/api/product/admin/products/$SPU/audit" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ATOKEN" \
  -d '{"pass":true,"remark":"超卖验证自动审核通过"}' >/dev/null \
  || { log "FATAL: SPU $SPU 平台审核失败"; exit 1; }
log "平台审核通过，轮询 SKU 上架可售状态（ON_SALE=3）"

#######################################
# 5. 轮询直到 SKU 状态=3 可售（最多 30s）
#######################################
SKUID=""
for _ in $(seq 1 30); do
  SKUS=$(curl -sf "$BASE/api/product/products/$SPU/skus" || true)
  SKUID=$(echo "$SKUS" | jq -r '.data[0].skuId // empty')
  SSTATUS=$(echo "$SKUS" | jq -r '.data[0].status // empty')
  if [ "$SSTATUS" = "3" ] && [ -n "$SKUID" ]; then
    break
  fi
  sleep 1
done
[ -n "${SKUID:-}" ] || { log "FATAL: SPU $SPU 未取到 skuId"; exit 1; }
[ "${SSTATUS:-}" = "3" ] || { log "FATAL: SKU ${SKUID} 30s 内未变为可售状态（当前=${SSTATUS}）"; exit 1; }
log "SKU 已可售 skuId=$SKUID"

#######################################
# 6. 平台建秒杀活动（totalStock=STOCK）并启用
#######################################
ACT_NAME="超卖验证秒杀-$EPOCH"
ACT_BODY=$(jq -n \
  --arg name "$ACT_NAME" \
  --arg start "$TS_START" \
  --arg end "$TS_END" \
  --argjson skuId "$SKUID" \
  --argjson price "$SECKILL_PRICE_FEN" \
  --argjson stock "$STOCK" \
  '{
    name:$name, type:10, startTime:$start, endTime:$end, ruleJson:"{}",
    seckillSkus:[{skuId:$skuId, seckillPriceFen:$price, totalStock:$stock}]
  }')
# 同 SPU：data 可能是裸 id 或对象，按类型归一（常见 id 字段名逐一兜底）。
ACTID=$(curl -sf -X POST "$BASE/api/marketing/admin/activities" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ATOKEN" \
  -d "$ACT_BODY" \
  | jq -r '.data | if type=="object" then (.activityId // .seckillActivityId // .id // empty) else . end // empty')
[ -n "${ACTID:-}" ] || { log "FATAL: 创建秒杀活动失败"; exit 1; }
log "已建秒杀活动 id=${ACTID}，启用中"

curl -sf -X POST "$BASE/api/marketing/admin/activities/$ACTID/status?status=1" \
  -H "Authorization: Bearer $ATOKEN" >/dev/null \
  || { log "FATAL: 活动 $ACTID 启用失败"; exit 1; }
log "秒杀活动已启用：2*$STOCK 并发将恰好成交 $STOCK"

#######################################
# 7. stdout 仅输出两行 eval（同时落盘）
#######################################
printf 'OVERSELL_SKU_ID=%s\nOVERSELL_ACTIVITY_ID=%s\n' "$SKUID" "$ACTID" \
  | tee "$WORKDIR/oversell.env"
