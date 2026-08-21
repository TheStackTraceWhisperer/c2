#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

CLUSTER_NAME="mmo-cluster"
GATEWAY_PORT=8888

echo "=== Starting MMO Cluster (k3d) ==="

mvn clean package -DskipTests
docker build -t mmo-platform:latest -f Dockerfile .

if ! k3d cluster list | grep -q "${CLUSTER_NAME}"; then
  k3d cluster create "${CLUSTER_NAME}" --port "${GATEWAY_PORT}:${GATEWAY_PORT}/udp@loadbalancer"
fi

k3d image import mmo-platform:latest -c "${CLUSTER_NAME}"

kubectl apply -f k8s/01-postgres.yaml
kubectl apply -f k8s/02-config.yaml
kubectl apply -f k8s/03-services.yaml
kubectl apply -f k8s/04-gateway.yaml

echo "[k8s] Waiting for deployments..."
kubectl rollout status deployment/postgres --timeout=90s
kubectl rollout status deployment/login-service --timeout=90s
kubectl rollout status deployment/lobby-service --timeout=90s
kubectl rollout status deployment/world-service --timeout=90s
kubectl rollout status deployment/zone-1-service --timeout=90s
kubectl rollout status deployment/zone-2-service --timeout=90s
kubectl rollout status deployment/gateway --timeout=90s

echo "Cluster ready! Gateway listening on localhost:${GATEWAY_PORT} (UDP/QUIC)"