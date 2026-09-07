#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="mmo-cluster"

echo "=== Tearing Down MMO Cluster ==="
pkill -f "kubectl port-forward.*kubernetes-dashboard" || true

if command -v k3d &>/dev/null && k3d cluster list | grep -q "${CLUSTER_NAME}"; then
  k3d cluster delete "${CLUSTER_NAME}"
fi

docker image rm -f platform-modulith:latest mmo-platform:latest mmo-tests:latest 2>/dev/null || true
echo "Cleanup complete."