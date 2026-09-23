#!/usr/bin/env bash
# 网关与鉴权冒烟（本地形态，默认 http://localhost:8080）。
# 用法: ./deploy/local/smoke.sh [BASE_URL]
# 退出码 0 全部断言通过；非 0 有失败项（输出 FAIL 行）。
set -uo pipefail
BASE=${1:-http://localhost:8080}
PASS=0; FAIL=0
TS=$(date +%s)
RND=$(awk 'BEGIN{srand(); print int(rand()*89999)+10000}')
SUF="${TS: -6}$RND"; SUF=${SUF: -8}
PHONE="139${SUF}"

ok()   { echo "PASS $*"; PASS=$((PASS+1)); }
bad()  { echo "FAIL $*"; FAIL=$((FAIL+1)); }
check() { # desc expected actual
  if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1 (expected=$2 actual=$3)"; fi
}
code() { curl -s -o /dev/null -w '%{http_code}' -m 5 "$@"; }

echo "== 0. 健康检查 =="
for p in 8080 8081 8082 8083 8084 8085 8086 8087; do
  c=$(code "http://localhost:$p/actuator/health")
  [ "$c" = "200" ] && ok "health :$p" || bad "health :$p ($c)"
done

echo "== 1. 公开白名单 =="
c=$(code "$BASE/api/product/products?pageNum=1&pageSize=1")
check "商品列表白名单" 200 "$c"
c=$(code "$BASE/api/marketing/coupons/center")
check "领券中心白名单" 200 "$c"

echo "== 2. 受保护接口无 token =="
c=$(code "$BASE/api/user/users/me")
check "/users/me 未登录 401" 401 "$c"
c=$(code "$BASE/api/order/orders?pageNum=1&pageSize=1")
check "订单列表未登录 401" 401 "$c"

echo "== 3. /inner 经网关硬阻断（含编码/穿越变体）=="
for u in "/api/user/inner/users/1" \
         "/api/user/..%2finner/users/1" \
         "/api/user/%252e%252e%252finner/x" \
         "/api/order/inner;/orders/x" \
         "/api/pay/inner/pays/x"; do
  c=$(code "$BASE$u")
  check "inner 阻断 $u" 404 "$c"
done

echo "== 4. 注册/登录/JWT =="
REG=$(curl -s -m 5 -X POST "$BASE/api/user/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"smoke_$TS$RND\",\"phone\":\"$PHONE\",\"password\":\"Smoke@12345\",\"nickname\":\"smoke\"}")
UID_=$(echo "$REG" | sed -n 's/.*"data":\([0-9]*\).*/\1/p')
[ -n "$UID_" ] && ok "注册 uid=$UID_" || bad "注册失败: $REG"

LOGIN=$(curl -s -m 5 -X POST "$BASE/api/user/auth/login" -H 'Content-Type: application/json' \
  -d "{\"account\":\"smoke_$TS$RND\",\"password\":\"Smoke@12345\"}")
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] && ok "登录取得 token" || bad "登录失败: $LOGIN"

ME=$(curl -s -m 5 "$BASE/api/user/users/me" -H "Authorization: Bearer $TOKEN")
echo "$ME" | grep -q "\"userId\":$UID_" && ok "JWT 身份正确" || bad "JWT 身份异常: $ME"

FORGED=$(curl -s -m 5 "$BASE/api/user/users/me" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 999999" -H "X-User-Type: 2")
echo "$FORGED" | grep -q "\"userId\":$UID_" && ok "伪造身份头被剥离" || bad "伪造头未被剥离: $FORGED"

TAMPER=$(code "$BASE/api/user/users/me" -H "Authorization: Bearer ${TOKEN}xx")
check "篡改 JWT 拒绝" 401 "$TAMPER"

echo
echo "结果: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
