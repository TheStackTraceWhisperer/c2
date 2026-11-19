#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "$ROOT_DIR"

# shellcheck source=scripts/_cluster-common.sh
source "${SCRIPT_DIR}/_cluster-common.sh"

echo "=== Starting MMO Cluster (k3d) ==="

build_modulith_image
ensure_k8s_tools
ensure_k3d_cluster
deploy_kubernetes_dashboard
ensure_jwt_secret "false"
import_modulith_image
deploy_platform_manifests

echo "[k8s] Waiting for dynamic world and zone pods..."
wait_for_pod "world-1"
wait_for_pod "world-2"

print_dashboard_banner
echo "Cluster ready! Gateway listening on localhost:${GATEWAY_PORT} (UDP/QUIC)"
