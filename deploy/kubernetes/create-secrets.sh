#!/usr/bin/env bash
# 从环境变量/KMS 引导 shop 命名空间的业务 Secret（生产推荐由密管流水线调用本脚本）。
#
# 安全约束：
#   - 真实值只从环境变量读取，不接受命令行参数（避免进入 shell history / ps 输出）；
#   - Secret 已存在时默认拒绝覆盖（防止占位符/旧值批量回滚覆盖已轮换密钥），
#     确认要轮换时显式 ALLOW_OVERWRITE=1；
#   - 任一字段缺失即中止（除 ADMIN_BOOTSTRAP_TOKEN 允许留空——生产引导功能必须关闭）；
#   - 本脚本只对接当前 context，调用方需自行保证 context 正确。
#
# 用法：
#   export MYSQL_USERNAME=... MYSQL_PASSWORD=... REDIS_PASSWORD=... JWT_SECRET=... INTERNAL_TOKEN=...
#   export DATA_ENC_KEY=...
#   export PAY_WECHAT_SECRET=... PAY_ALIPAY_SECRET=... PAY_BANK_SECRET=...
#   export PAY_UQR_SECRET=... PAY_HUABEI_SECRET=... PAY_BAITIAO_SECRET=...
#   bash deploy/kubernetes/create-secrets.sh
# Pod 侧环境变量映射（业务 Deployment env 用 secretKeyRef，键位见 15-secret-template.yaml）：
#   mysql-username->SHOP_DB_USER  mysql-password->SHOP_DB_PASSWORD
#   redis-password->SHOP_REDIS_PASSWORD
# prod 下 SHOP_SECURITY_REQUIRE_ENV_DB=true：DB 口令为空/root/不足 8 位会被 framework fail-fast。
set -euo pipefail
NS=${NAMESPACE:-shop}

require() { # <env-name>
  local name=$1
  if [ -z "${!name:-}" ]; then
    echo "FATAL: 环境变量 $name 未设置（真实密钥必须由密管/KMS 注入，禁止硬编码）" >&2
    exit 2
  fi
}

require MYSQL_USERNAME
require MYSQL_PASSWORD
require REDIS_PASSWORD
require JWT_SECRET
require INTERNAL_TOKEN
require DATA_ENC_KEY
require PAY_WECHAT_SECRET
require PAY_ALIPAY_SECRET
require PAY_BANK_SECRET
require PAY_UQR_SECRET
require PAY_HUABEI_SECRET
require PAY_BAITIAO_SECRET
# 生产留空：首个平台账号引导令牌（不 require，默认空串）
ADMIN_BOOTSTRAP_TOKEN=${ADMIN_BOOTSTRAP_TOKEN:-}

if kubectl -n "$NS" get secret shop-infra-secret >/dev/null 2>&1; then
  if [ "${ALLOW_OVERWRITE:-0}" != "1" ]; then
    echo "Secret shop-infra-secret 已存在，拒绝覆盖。确认轮换请显式 ALLOW_OVERWRITE=1 重跑。" >&2
    exit 3
  fi
  echo "WARN: ALLOW_OVERWRITE=1，将整体替换 shop-infra-secret（滚动重启后生效）"
fi

# --dry-run=client 生成清单再 apply：值不进进程参数，apply 幂等
kubectl -n "$NS" create secret generic shop-infra-secret \
  --from-literal=mysql-username="$MYSQL_USERNAME" \
  --from-literal=mysql-password="$MYSQL_PASSWORD" \
  --from-literal=redis-password="$REDIS_PASSWORD" \
  --from-literal=jwt-secret="$JWT_SECRET" \
  --from-literal=internal-token="$INTERNAL_TOKEN" \
  --from-literal=data-enc-key="$DATA_ENC_KEY" \
  --from-literal=admin-bootstrap-token="$ADMIN_BOOTSTRAP_TOKEN" \
  --from-literal=pay-mock-wechat-secret="$PAY_WECHAT_SECRET" \
  --from-literal=pay-mock-alipay-secret="$PAY_ALIPAY_SECRET" \
  --from-literal=pay-mock-bank-secret="$PAY_BANK_SECRET" \
  --from-literal=pay-mock-uqr-secret="$PAY_UQR_SECRET" \
  --from-literal=pay-mock-huabei-secret="$PAY_HUABEI_SECRET" \
  --from-literal=pay-mock-baitiao-secret="$PAY_BAITIAO_SECRET" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Secret shop-infra-secret 已写入 namespace/${NS}（字段数 13）"
echo "提示：轮换后需滚动重启工作负载让新值进入进程环境："
echo "  kubectl --context <ctx> -n $NS rollout restart deploy"
