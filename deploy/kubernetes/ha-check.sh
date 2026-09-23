#!/usr/bin/env bash
# K8s 高可用验收（仅 kind-kind；其他 context 直接拒绝执行）：
#  1) apply 生产清单 + kind 覆盖层（Secret 仅缺失时创建；单节点反亲和补丁；探针网段收紧）
#  2) 校验：每工作负载 2 副本、基线清单含 required 硬反亲和 + topologySpread、PDB、HPA、Service
#  3) PDB 强制执行：2 副本时第 1 个 Eviction 放行、第 2 个必须被 API Server 以 429 拒绝
#  4) 真实滚动/宕机不断流：150-240s 持续 HTTPS 流量期间
#       - 强杀一个网关 Pod、一个业务 Pod
#       - 网关与业务服务分别 rollout restart（maxUnavailable=0 + preStop + graceful）
#     全程失败率必须 < 2%（preStop 10s + graceful 30s 下预期接近 0）
#  5) TLS Ingress：自签 Secret + https://shop.example.com 443（--resolve）+ 80 强跳 443
# 前置：TAG=2.0.0 bash deploy/kubernetes/build-and-load-kind.sh；ingress-nginx/metrics-server 已装。
# 用法：deploy/kubernetes/ha-check.sh
set -uo pipefail
CTX=kind-kind
NS=shop
WORKLOADS="shop-gateway shop-user-service shop-product-service shop-marketing-service shop-order-service shop-pay-service shop-settlement-service shop-aftersale-service"
BUSINESS="shop-user-service shop-product-service shop-marketing-service shop-order-service shop-pay-service shop-settlement-service shop-aftersale-service"
K() { kubectl --context "$CTX" "$@"; }
PASS=0; FAIL=0
ok()  { echo "PASS $*"; PASS=$((PASS+1)); }
bad() { echo "FAIL $*"; FAIL=$((FAIL+1)); }

cd "$(dirname "$0")/../.."

# context 硬保护：只允许 kind-kind，杜绝误操作真实集群
CUR_CTX=$(kubectl config current-context 2>/dev/null || true)
[ "$CUR_CTX" = "$CTX" ] || { echo "FATAL: 当前 context='$CUR_CTX'，本脚本只允许在 $CTX 运行" >&2; exit 9; }

TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT

############################################
# 1. apply
############################################
echo "== 1. apply 清单 =="
# 先停宿主机直跑的 8 个应用：避免与 kind Pod 共享 RocketMQ 消费组（消息被两边分摊），
# 同时 kind Pod 使用独立 Nacos 分组 shop-ha-kind，互不发现。中间件容器保持运行。
if [ -x deploy/local/stop-apps.sh ]; then
  bash deploy/local/stop-apps.sh || true
fi
K apply -f deploy/kubernetes/00-namespace-config.yaml
K apply -f deploy/kubernetes/kind/00-kind-infra.yaml

# Secret：仅在不存在时创建，绝不批量覆盖（生产走 create-secrets.sh，值来自 KMS/环境变量）
if K -n $NS get secret shop-infra-secret >/dev/null 2>&1; then
  echo "   shop-infra-secret 已存在，保留不覆盖"
else
  K apply -f deploy/kubernetes/kind/05-kind-secret.yaml
fi

# 按当前宿主 LAN IP 修正 RocketMQ 端点（broker 通告地址三平面必须可达，见 deploy/rocketmq/README.md）
LAN_IP=${SHOP_BROKER_IP:-$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)}
if [ -n "$LAN_IP" ]; then
  echo "   patch ROCKETMQ_ENDPOINTS=$LAN_IP:18081"
  K -n $NS patch configmap shop-infra-config --type merge \
    -p "{\"data\":{\"ROCKETMQ_ENDPOINTS\":\"$LAN_IP:18081\"}}"
else
  echo "   WARN: 未能自动发现宿主 LAN IP（可用 SHOP_BROKER_IP=x.x.x.x 显式指定），沿用清单内值"
fi

K apply -f deploy/kubernetes/10-services.yaml

# 本地宿主 MySQL 连接上限保护（W7 K6_SMOKE 实证根因）：Druid 基线核心 30/其余 20，
# 7 个业务工作负载 ×2 副本 = 16 池，理论上限 16×20~30≈400 连接，而本地 MySQL 出厂
# max_connections=151（压测时 Threads_connected=146、errorCode 1040 Too many
# connections），商品/用户服务拿不到物理连接 → 订单 Feign 连锁 10008/10010。
# kind 验收统一 live-cap 每服务池=10（14 池×10=140，生产基线清单与代码分档不改：
# 生产 MySQL 为云托管、连接配额按实例规格规划，见 K8S §A 残留）。与调度补丁同款
# 只在 kind 集群打 env，不回写 10-services.yaml；set env 会触发一次新 RS 滚动，
# 随后的单节点旧 RS 缩 0/HPA cap 一并收敛。
for w in $BUSINESS; do
  K -n shop set env deploy/"$w" SHOP_DATASOURCE_TUNING_MAXACTIVE=10 >/dev/null
done
echo "   kind 验收：7 个业务工作负载 Druid maxActive live-cap=10（本地 MySQL 连接上限保护）"

# 单节点 kind 兼容：基线清单是 required 跨主机硬反亲和（生产必须），单节点会导致第 2 副本
# Pending。apply 后立即打 JSON 补丁放宽为 preferred + ScheduleAnyway（只改 kind 集群，
# 生产清单文件保持硬反亲和不变；多节点 kind 集群可导出 SKIP_SCHEDULING_PATCH=1 验证真反亲和）。
if [ "${SKIP_SCHEDULING_PATCH:-0}" != "1" ]; then
  NODE_CNT=$(K get nodes -o name | wc -l | tr -d ' ')
  if [ "$NODE_CNT" -lt 2 ]; then
    echo "   单节点集群：对 8 个工作负载打调度兼容补丁（preferred 反亲和）"
    for w in $WORKLOADS; do
      # 注意：kubectl 对跨行 -p 参数会按 YAML 解析并在转义引号处报错，patch 体必须单行。
      K -n $NS patch deploy/"$w" --type=json -p="[{\"op\":\"replace\",\"path\":\"/spec/template/spec/affinity\",\"value\":{\"podAntiAffinity\":{\"preferredDuringSchedulingIgnoredDuringExecution\":[{\"weight\":100,\"podAffinityTerm\":{\"labelSelector\":{\"matchLabels\":{\"app\":\"$w\"}},\"topologyKey\":\"kubernetes.io/hostname\"}}]}}},{\"op\":\"replace\",\"path\":\"/spec/template/spec/topologySpreadConstraints\",\"value\":[{\"maxSkew\":1,\"topologyKey\":\"kubernetes.io/hostname\",\"whenUnsatisfiable\":\"ScheduleAnyway\",\"labelSelector\":{\"matchLabels\":{\"app\":\"$w\"}}}]}]" >/dev/null
    done
    # 单节点滚动死锁解除：补丁只改 Deployment 模板，旧 ReplicaSet 仍保留硬反亲和；
    # K8s 反亲和对“现存 Pod”对称生效——旧 Running Pod 会把 preferred 的新 Pod 同样
    # 挡在门外（FailedScheduling: didn't satisfy existing pods anti-affinity rules），
    # 单节点上 maxSurge=1 滚动永远无法完成。这里把所有仍带硬反亲和的旧 RS 直接缩 0
    # （仅 kind 单节点验收：新 RS 两个副本随后即可调度，存在每服务数十秒的容量减半；
    # 多节点集群请用 SKIP_SCHEDULING_PATCH=1 走真反亲和，不执行本段）。
    for w in $WORKLOADS; do
      for rs in $(K -n $NS get rs -l app="$w" -o name); do
        hard=$(K -n $NS get "$rs" -o jsonpath='{.spec.template.spec.affinity.podAntiAffinity.requiredDuringSchedulingIgnoredDuringExecution}')
        if [ -n "$hard" ]; then
          echo "   $w: 旧 RS ${rs##*/} 仍带硬反亲和，缩 0 解除单节点滚动死锁"
          K -n $NS scale "$rs" --replicas=0 >/dev/null
        fi
      done
    done
    # 单节点资源上限只有 ~16Gi：滚动重启风暴下 HPA 会被 CPU 瞬时尖峰触发扩容到 3~6，
    # 多出副本全部 Pending（Insufficient cpu/memory），且第 3 节断言 replicas=2、
    # 第 4 节快照断言 product Pod 数=2 都会因此误判（W7 实证 HPA desired 3/4/5）。
    # 单节点验收把全部 HPA live-cap 到 [2,2] 并钉住 Deployment 副本=2（PERF_REPORT §7.5
    # 原本要求人工执行，这里自动化；HPA 对象仍存在，第 3 节 HPA 校验照过；
    # 多节点/容量场景用 SKIP_SCHEDULING_PATCH=1 或 MODE=capacity 集群，不执行本段）。
    for w in $WORKLOADS; do
      K -n $NS patch hpa/"$w"-hpa --type merge -p '{"spec":{"minReplicas":2,"maxReplicas":2}}' >/dev/null
      K -n $NS scale deploy/"$w" --replicas=2 >/dev/null
    done
    echo "   单节点集群：8 个 HPA 已 live-cap 到 2-2，Deployment 钉 2 副本"
  fi
fi

bash deploy/kubernetes/tls/gen-self-signed.sh shop.example.com

# HSTS：ingress-nginx 默认禁用 configuration-snippet（准入 webhook 会拒绝整个 Ingress），
# 安全基线也建议保持禁用；改用控制器全局 add-headers ConfigMap 注入 HSTS。
K -n ingress-nginx create configmap custom-headers \
  --from-literal='Strict-Transport-Security=max-age=31536000; includeSubDomains; preload' \
  --dry-run=client -o yaml | K apply -f -
K -n ingress-nginx get configmap ingress-nginx-controller >/dev/null 2>&1 \
  && K -n ingress-nginx patch configmap ingress-nginx-controller --type merge \
       -p '{"data":{"add-headers":"ingress-nginx/custom-headers"}}' \
  || echo "   WARN: 未找到 ingress-nginx-controller ConfigMap，HSTS 跳过（不阻断验收）"

# 不再 || true：准入拒绝（如误用 snippet 注解）必须让脚本失败，避免静默无 Ingress
K apply -f deploy/kubernetes/20-tls-ingress.yaml

# NetworkPolicy（R-K8S audit-first）：
#   默认只下发 30-networkpolicy.yaml 的零选中审计标记（任何 CNI 下均不阻断，
#   单节点 kind 安全）；显式 ENFORCE_NETWORKPOLICY=1 时额外下发 kind/40 强制
#   覆盖层（探针来源收紧为节点真实网段；强制 CNI 单节点可能断联，故默认关闭）。
K apply -f deploy/kubernetes/30-networkpolicy.yaml || true   # CNI 不支持时不阻塞验收
if [ "${ENFORCE_NETWORKPOLICY:-0}" = "1" ]; then
  NODE_IP=$(K get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
  NODE_CIDR=$(echo "$NODE_IP" | awk -F. '{print $1"."$2".0.0/16"}')
  echo "   ENFORCE_NETWORKPOLICY=1：探针网段收紧 ${NODE_CIDR}，下发强制覆盖层"
  sed "s#__KIND_NODE_CIDR__#$NODE_CIDR#g" deploy/kubernetes/kind/40-kind-networkpolicy.yaml \
    | K apply -f - || true   # CNI 不支持 NetworkPolicy 时不阻塞验收
else
  echo "   NetworkPolicy 仅 audit（零选中标记）；强制验证：ENFORCE_NETWORKPOLICY=1 $0"
fi

############################################
# 2. rollout
############################################
echo "== 2. 等待 rollout（10 分钟上限/工作负载） =="
for d in $(K -n $NS get deploy -o name); do
  K -n $NS rollout status "$d" --timeout=10m || { bad "rollout $d"; }
done

############################################
# 3. HA 资源校验
############################################
echo "== 3. HA 资源校验 =="
for w in $WORKLOADS; do
  reps=$(K -n $NS get deploy/$w -o jsonpath='{.spec.replicas}')
  [ "$reps" = "2" ] && ok "$w replicas=2" || bad "$w replicas=$reps"
  ready=$(K -n $NS get deploy/$w -o jsonpath='{.status.readyReplicas}')
  [ "${ready:-0}" = "2" ] && ok "$w ready=2" || bad "$w readyReplicas=${ready:-0}"
  grace=$(K -n $NS get deploy/$w -o jsonpath='{.spec.template.spec.terminationGracePeriodSeconds}')
  [ "${grace:-}" = "60" ] && ok "$w terminationGracePeriodSeconds=60" || bad "$w grace=$grace"
  K -n $NS get deploy/$w -o jsonpath='{.spec.template.spec.containers[0].lifecycle.preStop.exec.command}' \
      | grep -q sleep && ok "$w preStop" || bad "$w 缺 preStop"
  K -n $NS get deploy/$w -o jsonpath='{.spec.template.spec.containers[0].startupProbe.httpGet.path}' \
      | grep -q liveness && ok "$w startupProbe" || bad "$w 缺 startupProbe"
  K -n $NS get pdb/$w-pdb >/dev/null 2>&1 && ok "$w PDB" || bad "$w 缺 PDB"
done
# 基线【清单文件】必须是 required 硬反亲和 + topologySpread（防止有人为了 kind 顺手改软生产清单）
grep -q requiredDuringSchedulingIgnoredDuringExecution deploy/kubernetes/10-services.yaml \
  && ok "业务基线清单 required 硬反亲和" || bad "业务基线清单缺少 required 硬反亲和"
grep -q topology.kubernetes.io/zone deploy/kubernetes/10-services.yaml \
  && ok "业务基线清单 跨可用区 spread" || bad "缺少跨可用区 spread"
grep -q requiredDuringSchedulingIgnoredDuringExecution deploy/kubernetes/00-namespace-config.yaml \
  && ok "网关基线清单 required 硬反亲和" || bad "网关基线清单缺少 required 硬反亲和"
for w in $WORKLOADS; do
  K -n $NS get hpa/$w-hpa >/dev/null 2>&1 && ok "$w HPA" || bad "$w 缺 HPA"
done
K -n $NS get svc/shop-gateway >/dev/null && ok "gateway Service" || bad "缺 gateway Service"
# R-K8S：7 个业务 ClusterIP Service（名称与网关 lb:// 及 Nacos 服务名一致）
for w in $BUSINESS; do
  K -n $NS get svc/$w >/dev/null 2>&1 && ok "$w Service" || bad "$w 缺 Service"
done
K get ns/$NS >/dev/null 2>&1 && ok "namespace/$NS bootstrap" || bad "缺 namespace"

############################################
# 4. PDB 强制执行（policy/v1 Eviction 子资源）
############################################
echo "== 4. PDB 强制执行 =="
evict_pod() { # <pod>  → 输出 HTTP 风格结果：ALLOW / BLOCKED / OTHER:<body>
  local pod=$1 body
  # kubectl 1.32+ 的 `create -f -` 已不再本地识别 policy/v1 Eviction
  # （报 "no matches for kind Eviction"），改用 --raw 直接 POST eviction 子资源；
  # 击穿 PDB 时 API Server 返回 429，body 含 "cannot evict pod as it would violate
  # the pod's disruption budget"（reason=TooManyRequests）。
  body=$(K -n $NS create --raw "/api/v1/namespaces/$NS/pods/$pod/eviction" -f - <<JSON 2>&1
{"apiVersion":"policy/v1","kind":"Eviction","metadata":{"name":"$pod","namespace":"$NS"}}
JSON
)
  if [ $? = 0 ]; then echo "ALLOW"
  elif echo "$body" | grep -qi "disruption budget\|TooManyRequests"; then echo "BLOCKED"
  else echo "OTHER:$body"; fi
}
K -n $NS rollout status deploy/shop-product-service --timeout=3m >/dev/null
# 关键：两个候选 Pod 必须在第一次驱逐【之前】快照。驱逐 P1 后 maxSurge=1 会在
# 1 秒内造出一个未 Ready 的 surge 替换 Pod；K8s 对未 Ready Pod 的驱逐不消耗 PDB
# 预算（它本就不计入 currentHealthy）。若事后按名字选「另一个 Pod」，随机后缀排序
# 可能恰好选中这个 surge Pod，第二次驱逐会被合法 ALLOW——这正是前几轮验收唯一
# FAIL 的真正根因（PDB 行为本身正确，是测试选错了靶子；已实测：先快照 Ready
# 幸存者再连续驱逐，API Server 稳定返回 429）。
PRODUCT_PODS=$(K -n $NS get pod -l app=shop-product-service \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort | tr '\n' ' ')
P1=$(echo $PRODUCT_PODS | awk '{print $1}')
P2=$(echo $PRODUCT_PODS | awk '{print $2}')
NPOD=$(echo $PRODUCT_PODS | wc -w | tr -d ' ')
if [ "$NPOD" != "2" ] || [ -z "$P2" ]; then
  bad "PDB 前置条件异常：shop-product-service 快照 Pod 数=${NPOD}（应为 2），跳过驱逐断言"
else
R1=$(evict_pod "$P1"); echo "   evict $P1 -> $R1"
[ "$R1" = "ALLOW" ] && ok "PDB：2 副本时第 1 个驱逐放行" || bad "PDB 首次驱逐异常: $R1"
# 消除 PDB 控制器状态传播竞态：第一次驱逐后必须轮询到 disruptionsAllowed=0
# （currentHealthy 降到 minAvailable=1）再发第二次；否则 API 可能在控制器重算前
# 依据旧状态把第二个也放行（60s grace + preStop 下实测存在该窗口）。
DEADLINE=$((SECONDS+90)); ALLOWED_ZERO=0; DA=""; HEALTHY=""
while [ $SECONDS -lt $DEADLINE ]; do
  DA=$(K -n $NS get pdb/shop-product-service-pdb -o jsonpath='{.status.disruptionsAllowed}')
  HEALTHY=$(K -n $NS get pdb/shop-product-service-pdb -o jsonpath='{.status.currentHealthy}')
  if [ "$DA" = "0" ]; then ALLOWED_ZERO=1; echo "   PDB 状态已收敛：disruptionsAllowed=0 currentHealthy=${HEALTHY:-?}"; break; fi
  sleep 1
done
[ "$ALLOWED_ZERO" = "1" ] || echo "   WARN: 90s 内未观察到 disruptionsAllowed=0（最后 DA=${DA} healthy=${HEALTHY:-?}），仍尝试第二次驱逐"
# P2 是驱逐前快照的 Ready 幸存者（不是 surge 新 Pod）；再确认它此刻仍 Ready
READY2=$(K -n $NS get pod "$P2" -o jsonpath='{.status.containerStatuses[0].ready}')
[ "$READY2" = "true" ] || echo "   WARN: 快照幸存者 $P2 当前 ready=${READY2}（异常），结果可能不可信"
R2=$(evict_pod "$P2"); echo "   evict $P2 (Ready 幸存者) -> $R2"
[ "$R2" = "BLOCKED" ] && ok "PDB：再驱逐会击穿 minAvailable=1，API Server 429 拒绝" \
  || bad "PDB 未阻止第二次驱逐（${R2}，可重跑）"
fi
K -n $NS rollout status deploy/shop-product-service --timeout=3m >/dev/null

############################################
# 5. 持续真实流量下 pod-kill + 滚动重启
############################################
echo "== 5. 真实流量：pod-kill + rollout restart（约 4 分钟） =="
# 入口端口探测：kind 通常通过 extraPortMappings 把宿主 80/443 直接映射进节点
# （ingress svc 的 nodePort 并不在宿主监听）；其他部署形态则走 nodePort。
# 依次探测候选端口，选真正返回 200 的，避免"探针连不通→失败率 100%"假故障。
NP443=$(K -n ingress-nginx get svc ingress-nginx-controller -o jsonpath='{.spec.ports[?(@.port==443)].nodePort}')
pick_https_port() {
  # 可达性判据：ingress/网关层返回【任意 HTTP 状态码】即入口通——429（被自身限流桶
  # 限流）/503（滚动瞬态）/404 都证明 TLS 入口→ingress→Service 链路存在；只有 000
  # （连接失败/超时）才算不通。W7 实证：严格要求 200 会被滚动窗口 503 或探测者自己
  # 撞出的 429 误杀（exit 8），而随后 4 分钟流量线程本来就以 200 率做硬判定。
  # 对每个候选端口最多重试 15 次（~45s），覆盖滚动收敛/令牌桶补充窗口。
  local port code try
  for port in 443 $NP443; do
    [ -z "$port" ] && continue
    for try in $(seq 1 15); do
      code=$(curl -sk -o /dev/null -w '%{http_code}' -m 3 \
        --resolve shop.example.com:$port:127.0.0.1 \
        "https://shop.example.com:$port/api/product/products?pageNum=1&pageSize=1" || echo 000)
      [ "$code" != "000" ] && { echo "$port"; return 0; }
      sleep 3
    done
  done
  return 1
}
INGRESS_PORT=$(pick_https_port) || { echo "FATAL: 443/nodePort($NP443) 均无法访问 Ingress" >&2; exit 8; }
echo "   Ingress HTTPS 入口端口: ${INGRESS_PORT}（443 映射或 nodePort 自动探测）"
URL="https://shop.example.com:$INGRESS_PORT/api/product/products?pageNum=1&pageSize=1"
: > "$TMPD/codes"
# 流量线程：0.5s 一发，直到 stop 文件出现；记录 HTTP 码（000 = 连接失败/超时）
(
  while [ ! -f "$TMPD/stop" ]; do
    code=$(curl -sk -o /dev/null -w '%{http_code}' -m 3 --resolve shop.example.com:$INGRESS_PORT:127.0.0.1 "$URL" || echo 000)
    echo "$code" >> "$TMPD/codes"
    sleep 0.5
  done
) &
TPID=$!
sleep 5   # 基线 5s 必须全绿

GWPOD=$(K -n $NS get pod -l app=shop-gateway -o jsonpath='{.items[0].metadata.name}')
ORDPOD=$(K -n $NS get pod -l app=shop-order-service -o jsonpath='{.items[0].metadata.name}')
echo "   kill gateway/$GWPOD"
K -n $NS delete pod "$GWPOD" --wait=false
sleep 15
echo "   kill order/$ORDPOD"
K -n $NS delete pod "$ORDPOD" --wait=false
sleep 15
echo "   rollout restart shop-gateway（maxUnavailable=0）"
K -n $NS rollout restart deploy/shop-gateway
K -n $NS rollout status deploy/shop-gateway --timeout=5m
sleep 5
echo "   rollout restart shop-order-service（maxUnavailable=0）"
K -n $NS rollout restart deploy/shop-order-service
K -n $NS rollout status deploy/shop-order-service --timeout=5m
sleep 10
touch "$TMPD/stop"; wait "$TPID" 2>/dev/null || true

TOTAL=$(wc -l < "$TMPD/codes" | tr -d ' ')
KO=$(grep -vc '^200$' "$TMPD/codes" || true)
# 基线前 10 个采样必须全 200（无干扰窗口）
BASELINE_BAD=$(head -10 "$TMPD/codes" | grep -vc '^200$' || true)
RATE=$(awk "BEGIN{printf \"%.2f\", $KO*100/$TOTAL}")
echo "   请求 $TOTAL 失败 $KO 失败率 ${RATE}%（基线窗口失败 $BASELINE_BAD/10）"
[ "$BASELINE_BAD" = "0" ] && ok "扰动前基线全绿" || bad "基线窗口存在失败=${BASELINE_BAD}（环境本身不稳定）"
[ "$TOTAL" -ge 100 ] && ok "流量样本非空（${TOTAL} >=100）" || bad "样本过少 ${TOTAL}（脚本/探针异常）"
awk "BEGIN{exit !($RATE < 2)}" && ok "kill+滚动期间失败率<2%" || bad "失败率=${RATE}%（>=2%）"

############################################
# 6. HTTP 强跳 HTTPS
############################################
echo "== 6. HTTP 强跳 HTTPS =="
NP80=$(K -n ingress-nginx get svc ingress-nginx-controller -o jsonpath='{.spec.ports[?(@.port==80)].nodePort}')
HPORT=""
for cand in 80 $NP80; do
  [ -z "$cand" ] && continue
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 3 \
    --resolve shop.example.com:$cand:127.0.0.1 \
    "http://shop.example.com:$cand/api/product/products" || echo 000)
  if [ "$code" = "308" ] || [ "$code" = "301" ]; then HPORT=$cand; break; fi
done
[ -z "$HPORT" ] && { bad "80 端口候选(80/$NP80)均无 308/301 跳转"; } || {
loc=$(curl -s -o /dev/null -w '%{http_code}' --resolve shop.example.com:$HPORT:127.0.0.1 \
  "http://shop.example.com:$HPORT/api/product/products")
{ [ "$loc" = "308" ] || [ "$loc" = "301" ]; } && ok "80→443 跳转 ($loc)" || bad "未强制跳转 ($loc)"
}

echo
echo "HA 结果: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
