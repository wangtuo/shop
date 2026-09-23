#!/usr/bin/env bash
# 构建全部 8 个镜像并加载进本地 kind 集群（kind-kind），用于高可用验收。
# 镜像名与 deploy/kubernetes 清单保持一致：registry.example.com/shop/<module>:2.0.0
# 清单使用固定 tag（非 latest），imagePullPolicy 默认 IfNotPresent，加载后不会去拉远端。
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
# SDKMAN 初始化脚本内含未定义变量引用，必须临时关闭 nounset（与 deploy/local/start-apps.sh 同）
set +u
source ~/.sdkman/bin/sdkman-init.sh
set -u

TAG=${TAG:-2.0.0}
MODULES=(
  "shop-gateway/target/shop-gateway.jar:shop-gateway"
  "shop-user-service/target/shop-user-service.jar:shop-user-service"
  "shop-product-service/target/shop-product-service.jar:shop-product-service"
  "shop-marketing-service/target/shop-marketing-service.jar:shop-marketing-service"
  "shop-order-service/target/shop-order-service.jar:shop-order-service"
  "shop-pay-service/target/shop-pay-service.jar:shop-pay-service"
  "shop-settlement-service/target/shop-settlement-service.jar:shop-settlement-service"
  "shop-aftersale-service/target/shop-aftersale-service.jar:shop-aftersale-service"
)

echo "== 1. 打包 =="
mvn -q -DskipTests clean package

echo "== 2. 构建镜像（arm64 原生）=="
for item in "${MODULES[@]}"; do
  jar=${item%%:*}; name=${item##*:}
  [ -f "$ROOT/$jar" ] || { echo "缺少 $jar"; exit 1; }
  img="registry.example.com/shop/$name:$TAG"
  docker build -q -f deploy/docker/Dockerfile --build-arg "JAR_FILE=$jar" -t "$img" "$ROOT" >/dev/null
  echo "  built $img"
done

echo "== 3. 加载进 kind-kind =="
for item in "${MODULES[@]}"; do
  name=${item##*:}
  kind load docker-image "registry.example.com/shop/$name:$TAG" --name kind
done

echo "完成。随后："
echo "  kubectl --context kind-kind apply -f deploy/kubernetes/"
echo "  kubectl --context kind-kind apply -f deploy/kubernetes/kind/"
