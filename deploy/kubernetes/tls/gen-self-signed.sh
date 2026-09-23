#!/usr/bin/env bash
# 生成自签 TLS 证书 Secret（仅用于本地 kind / 内网验收，生产用 cert-manager + 真实 CA）。
# 用法: gen-self-signed.sh [host ...]   默认 host 与 20-tls-ingress.yaml 一致
set -euo pipefail
HOSTS=("$@"); [ ${#HOSTS[@]} -eq 0 ] && HOSTS=(shop.example.com)
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

{
  echo "[req]"
  echo "distinguished_name = dn"
  echo "x509_extensions = v3_req"
  echo "prompt = no"
  echo "[dn]"
  echo "CN = ${HOSTS[0]}"
  echo "[v3_req]"
  printf 'subjectAltName = '
  sep=""
  for h in "${HOSTS[@]}"; do printf '%sDNS:%s' "$sep" "$h"; sep=","; done
  echo
} > "$DIR/openssl.cnf"

openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
  -keyout "$DIR/tls.key" -out "$DIR/tls.crt" -config "$DIR/openssl.cnf"

kubectl --context kind-kind create namespace shop --dry-run=client -o yaml | kubectl apply -f -
kubectl --context kind-kind -n shop create secret tls shop-gateway-tls \
  --cert="$DIR/tls.crt" --key="$DIR/tls.key" --dry-run=client -o yaml | kubectl apply -f -
echo "Secret shop/shop-gateway-tls 已创建（hosts: ${HOSTS[*]}）"
