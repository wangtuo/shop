#!/usr/bin/env bash
# 在 kind-kind 安装 metrics-server（HPA 取数依赖）。
# kind 节点自签证书，必须加 --kubelet-insecure-tls，否则 metrics-server 无法抓取。
set -euo pipefail
CTX=kind-kind
URL=https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

kubectl --context "$CTX" apply -f "$URL"
kubectl --context "$CTX" -n kube-system patch deployment metrics-server --type=json \
  -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
echo "等待 metrics-server 就绪..."
kubectl --context "$CTX" -n kube-system rollout status deployment/metrics-server --timeout=180s
kubectl --context "$CTX" top nodes
