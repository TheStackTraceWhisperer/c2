#!/usr/bin/env bash
# Shared k3d cluster bootstrap helpers sourced by up-cluster.sh and run-e2e.sh.

CLUSTER_NAME="${CLUSTER_NAME:-mmo-cluster}"
GATEWAY_PORT="${GATEWAY_PORT:-8888}"
DB_PORT="${DB_PORT:-5432}"
DASHBOARD_PORT="${DASHBOARD_PORT:-8443}"
CERTS_DIR="${CERTS_DIR:-${ROOT_DIR}/target/certs}"

build_modulith_image() {
  echo "[build] Packaging platform-modulith with Micronaut docker plugin..."
  ./mvnw clean package -DskipTests -Dpackaging=docker
}

ensure_k8s_tools() {
  if ! command -v k3d &> /dev/null; then
    echo "[prereq] Installing k3d..."
    curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | TAG=v5.6.0 bash
  fi

  if ! command -v kubectl &> /dev/null; then
    echo "[prereq] Installing kubectl..."
    curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
    chmod +x kubectl
    mkdir -p ~/.local/bin
    mv kubectl ~/.local/bin/
    export PATH="${HOME}/.local/bin:${PATH}"
  fi
}

ensure_k3d_cluster() {
  if ! k3d cluster list | grep -q "${CLUSTER_NAME}"; then
    echo "[k3d] Creating cluster '${CLUSTER_NAME}'..."
    k3d cluster create "${CLUSTER_NAME}" \
      --port "${GATEWAY_PORT}:${GATEWAY_PORT}/udp@loadbalancer" \
      --port "${DB_PORT}:${DB_PORT}@loadbalancer"
  fi

  # Ensure kubectl context points to the mmo-cluster
  k3d kubeconfig merge "${CLUSTER_NAME}" --kubeconfig-switch-context >/dev/null 2>&1 || true
  kubectl config use-context "k3d-${CLUSTER_NAME}" >/dev/null 2>&1 || true
}

deploy_kubernetes_dashboard() {
  echo "[k8s] Deploying Kubernetes Dashboard..."
  kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.7.0/aio/deploy/recommended.yaml

  cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ServiceAccount
metadata:
  name: admin-user
  namespace: kubernetes-dashboard
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: admin-user
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
- kind: ServiceAccount
  name: admin-user
  namespace: kubernetes-dashboard
EOF

  echo "[k8s] Waiting for Kubernetes Dashboard deployment..."
  kubectl rollout status deployment/kubernetes-dashboard -n kubernetes-dashboard --timeout=60s

  pkill -f "kubectl port-forward.*kubernetes-dashboard.*${DASHBOARD_PORT}" || true
  nohup kubectl port-forward --address 0.0.0.0 -n kubernetes-dashboard svc/kubernetes-dashboard "${DASHBOARD_PORT}:443" > /dev/null 2>&1 &
  sleep 2

  DASHBOARD_TOKEN=$(kubectl -n kubernetes-dashboard create token admin-user --duration=24h)
}

ensure_jwt_secret() {
  local force_regenerate="${1:-false}"
  echo "[crypto] Ensuring JWT secret exists..."
  mkdir -p "${CERTS_DIR}"
  if [ "${force_regenerate}" = "true" ] || [ ! -f "${CERTS_DIR}/jwt.key" ] || [ ! -f "${CERTS_DIR}/jwt.pub" ]; then
    openssl genpkey -algorithm RSA -out "${CERTS_DIR}/jwt.key" -pkeyopt rsa_keygen_bits:2048
    openssl rsa -pubout -in "${CERTS_DIR}/jwt.key" -out "${CERTS_DIR}/jwt.pub"
  fi

  kubectl create secret generic jwt-keypair-secret \
    --from-file=tls.key="${CERTS_DIR}/jwt.key" \
    --from-file=tls.crt="${CERTS_DIR}/jwt.pub" \
    --dry-run=client -o yaml | kubectl apply -f -
}

import_modulith_image() {
  echo "[k3d] Importing platform-modulith:latest into cluster..."
  k3d image import platform-modulith:latest -c "${CLUSTER_NAME}"
}

deploy_platform_manifests() {
  echo "[k8s] Applying manifests..."
  kubectl apply -f k8s/01-postgres.yaml
  kubectl apply -f k8s/02-config.yaml
  kubectl apply -f k8s/03-services.yaml
  kubectl apply -f k8s/04-gateway.yaml

  echo "[k8s] Triggering rolling restart of static deployments..."
  kubectl rollout restart deployment/postgres \
    deployment/login-service \
    deployment/lobby-service \
    deployment/mmo-operator \
    deployment/gateway

  echo "[k8s] Waiting for static cluster deployments to be ready..."
  kubectl rollout status deployment/postgres --timeout=90s
  kubectl rollout status deployment/login-service --timeout=90s
  kubectl rollout status deployment/lobby-service --timeout=90s
  kubectl rollout status deployment/mmo-operator --timeout=90s
  kubectl rollout status deployment/gateway --timeout=90s
}

wait_for_pod() {
  local pod_name="$1"
  local retries=30
  echo -n "Waiting for pod ${pod_name} to appear and become Ready..."
  until kubectl get pod "${pod_name}" >/dev/null 2>&1; do
    sleep 1
    retries=$((retries - 1))
    if [ $retries -le 0 ]; then
      echo " TIMEOUT (Pod not created)"
      return 1
    fi
  done
  kubectl wait --for=condition=Ready "pod/${pod_name}" --timeout=60s
  echo " READY."
}

print_dashboard_banner() {
  echo ""
  echo "=========================================================================="
  echo " Kubernetes Dashboard is live and accessible!"
  echo " URL:   https://127.0.0.1:${DASHBOARD_PORT}"
  echo " Token: ${DASHBOARD_TOKEN}"
  echo "=========================================================================="
  echo ""
}
