#!/usr/bin/env bash
# 从零重建 kind HA 验收集群（context kind-kind）：
#   删旧集群 → 按 kind-cluster.yaml 建集群（80/443 端口映射 + ingress-ready 标签）
#   → 装 ingress-nginx（kind 专用 manifest，hostPort 模式）→ 装 metrics-server（HPA 取数）
#
# 幂等：可重复执行，会删除并重建整个集群（不影响宿主机 docker compose 上的中间件）。
# 用法：bash deploy/kubernetes/kind/reset-kind.sh
set -euo pipefail
cd "$(dirname "$0")/../../.."
CTX=kind-kind
INGRESS_URL=${INGRESS_NGINX_URL:-https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml}

echo "== 1. 重建 kind 集群 =="
kind delete cluster --name kind || true
kind create cluster --name kind --config deploy/kubernetes/kind/kind-cluster.yaml

echo "== 2. 安装 ingress-nginx（kind hostPort 版） =="
kubectl --context "$CTX" apply -f "$INGRESS_URL"
kubectl --context "$CTX" -n ingress-nginx rollout status deployment/ingress-nginx-controller --timeout=300s

echo "== 3. 安装 metrics-server =="
bash deploy/kubernetes/kind/install-metrics-server.sh

echo "集群就绪。后续：TAG=2.0.0 bash deploy/kubernetes/build-and-load-kind.sh && bash deploy/kubernetes/ha-check.sh"
