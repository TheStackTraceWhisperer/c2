#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "$ROOT_DIR"

# shellcheck source=scripts/_cluster-common.sh
source "${SCRIPT_DIR}/_cluster-common.sh"

echo "=== Kubernetes k3d E2E Cluster Harness ==="

ensure_docker
ensure_k8s_tools
build_modulith_image
ensure_k3d_cluster
deploy_kubernetes_dashboard
print_dashboard_banner
ensure_jwt_secret "true"
import_modulith_image

POD_LOG_FILE="${ROOT_DIR}/e2e-pods.log"

dump_pod_logs() {
  {
    echo "=== POD STATUS ==="
    kubectl get pods -A || true
    echo ""
    echo "=== GATEWAY LOGS ==="
    kubectl logs deployment/gateway --tail=200 || true
    echo ""
    echo "=== LOGIN LOGS ==="
    kubectl logs deployment/login-service --tail=200 || true
    echo ""
    echo "=== LOBBY LOGS ==="
    kubectl logs deployment/lobby-service --tail=200 || true
    echo ""
    echo "=== OPERATOR LOGS ==="
    kubectl logs deployment/mmo-operator --tail=200 || true
    echo ""
    echo "=== WORLD-1 LOGS ==="
    kubectl logs pod/world-1 --tail=100 || true
    echo ""
    echo "=== ZONE 230 LOGS ==="
    kubectl logs pod/w1-inst-1-z230 --tail=100 || true
  } > "${POD_LOG_FILE}" 2>&1
}

cleanup_on_exit() {
  local exit_code=$?
  dump_pod_logs
  if [ $exit_code -ne 0 ]; then
    echo "[e2e] Test execution failed with exit code ${exit_code}. Pod logs written to: ${POD_LOG_FILE}" >&2
  else
    echo "[e2e] Pod logs written to: ${POD_LOG_FILE}"
  fi
}
trap cleanup_on_exit EXIT

deploy_platform_manifests

echo "[k8s] Waiting for MMO Operator to reconcile and spin up dynamic World & FFXI Zone pods..."
# Wait for World Coordinators
wait_for_pod "world-1"
wait_for_pod "world-2"

# Wait for Starter City Static Shards (World 1: Instances 1-6)
wait_for_pod "w1-inst-1-z230"
wait_for_pod "w1-inst-2-z231"
wait_for_pod "w1-inst-3-z234"
wait_for_pod "w1-inst-4-z235"
wait_for_pod "w1-inst-5-z238"
wait_for_pod "w1-inst-6-z239"
wait_for_tcp_port 127.0.0.1 "${DB_PORT}"

# Run Integration Test Suite against live k3d endpoints
echo "[test] Executing Integration Test Suite (IT) against live k3d cluster..."
./mvnw test -pl platform-modulith -am \
  -Dtest="com.example.**.*IT" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dapp.gateway.host=127.0.0.1 \
  -Dapp.gateway.port=${GATEWAY_PORT} \
  -Ddatasources.default.url="jdbc:postgresql://localhost:${DB_PORT}/mmo_platform" \
  -Ddatasources.default.maximum-pool-size=3 \
  -Ddatasources.default.minimum-idle=1 \
  -Dapp.auth.private-key-path="${CERTS_DIR}/jwt.key" \
  -Dapp.auth.public-key-path="${CERTS_DIR}/jwt.pub"

echo "[e2e] All FFXI multi-schema cluster tests passed successfully!"
