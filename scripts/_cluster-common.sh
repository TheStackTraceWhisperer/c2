#!/usr/bin/env bash
# Shared k3d cluster bootstrap helpers sourced by up-cluster.sh and run-e2e.sh.

CLUSTER_NAME="${CLUSTER_NAME:-mmo-cluster}"
GATEWAY_PORT="${GATEWAY_PORT:-8888}"
DB_PORT="${DB_PORT:-5432}"
DASHBOARD_PORT="${DASHBOARD_PORT:-8443}"
CERTS_DIR="${CERTS_DIR:-${ROOT_DIR}/target/certs}"

build_modulith_image() {
  echo "[build] Packaging platform-modulith with Micronaut docker plugin..."
  ./mvnw clean package -pl platform-modulith -am -DskipTests -Dpackaging=docker
}

ensure_docker() {
  if ! command -v docker &> /dev/null; then
    echo "[prereq] ERROR: 'docker' command not found. Install Docker Engine (e.g. docker.io) first." >&2
    return 1
  fi
  if ! docker info >/dev/null 2>&1; then
    echo "[prereq] ERROR: Docker daemon is not reachable by user '$(id -un)'." >&2
    return 1
  fi
}

ensure_k8s_tools() {
  mkdir -p "${HOME}/.local/bin"
  export PATH="${HOME}/.local/bin:${PATH}"

  if ! command -v k3d &> /dev/null; then
    echo "[prereq] Installing k3d into ${HOME}/.local/bin..."
    curl -fsSL https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | K3D_INSTALL_DIR="${HOME}/.local/bin" USE_SUDO="false" TAG=v5.9.0 bash
  fi

  if ! command -v kubectl &> /dev/null; then
    echo "[prereq] Installing kubectl into ${HOME}/.local/bin..."
    local tmp_kubectl
    tmp_kubectl="$(mktemp)"
    local stable_ver
    stable_ver="$(curl -fsSL https://dl.k8s.io/release/stable.txt)"
    curl -fsSL -o "${tmp_kubectl}" "https://dl.k8s.io/release/${stable_ver}/bin/linux/amd64/kubectl"
    chmod +x "${tmp_kubectl}"
    mv "${tmp_kubectl}" "${HOME}/.local/bin/kubectl"
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
  kubectl rollout status deployment/kubernetes-dashboard -n kubernetes-dashboard --timeout=120s

  pkill -f "kubectl port-forward.*kubernetes-dashboard.*${DASHBOARD_PORT}" || true
  nohup kubectl port-forward --address 0.0.0.0 -n kubernetes-dashboard svc/kubernetes-dashboard "${DASHBOARD_PORT}:443" </dev/null >/dev/null 2>&1 &
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
  echo "[k3d] Importing platform-modulith:latest into cluster '${CLUSTER_NAME}'..."
  local nodes
  nodes=$(docker ps --filter "label=k3d.cluster=${CLUSTER_NAME}" --format '{{.Names}}' | grep -E -- '-(server|agent)-[0-9]+$' || true)
  if [ -z "${nodes}" ]; then
    echo "[k3d] ERROR: No k3d server/agent nodes found for cluster '${CLUSTER_NAME}'." >&2
    return 1
  fi

  local node
  local imported_images
  for node in ${nodes}; do
    echo "[k3d] Loading platform-modulith:latest into node '${node}'..."
    docker save platform-modulith:latest | docker exec -i "${node}" ctr -n k8s.io images import -
    imported_images="$(docker exec "${node}" ctr -n k8s.io images ls -q)"
    if ! grep -q "platform-modulith:latest" <<< "${imported_images}"; then
      echo "[k3d] ERROR: Image platform-modulith:latest not found in node '${node}' after import." >&2
      return 1
    fi
  done

  echo "[k3d] Image imported and verified on all cluster nodes."
}

deploy_platform_manifests() {
  echo "[k8s] Applying manifests..."
  kubectl apply -f k8s/01-postgres.yaml
  kubectl apply -f k8s/02-config.yaml
  kubectl apply -f k8s/03-services.yaml
  kubectl apply -f k8s/04-gateway.yaml

  echo "[k8s] Pausing mmo-operator and cleaning up stale dynamic world/zone pods..."
  kubectl scale deployment/mmo-operator --replicas=0
  kubectl wait --for=delete pod -l app=mmo-operator --timeout=60s >/dev/null 2>&1 || true
  kubectl delete pod -l 'app.mmo/component in (world,zone)' --ignore-not-found=true --wait=true --timeout=60s

  echo "[k8s] Restarting PostgreSQL and waiting for it to be ready..."
  kubectl rollout restart deployment/postgres
  kubectl rollout status deployment/postgres --timeout=180s

  echo "[k8s] Triggering rolling restart of static platform deployments..."
  kubectl rollout restart deployment/login-service \
    deployment/lobby-service \
    deployment/gateway

  echo "[k8s] Waiting for static cluster deployments to be ready..."
  kubectl rollout status deployment/login-service --timeout=240s
  kubectl rollout status deployment/lobby-service --timeout=240s
  kubectl rollout status deployment/gateway --timeout=240s

  echo "[k8s] Starting mmo-operator and waiting for it to be ready..."
  kubectl scale deployment/mmo-operator --replicas=1
  kubectl rollout status deployment/mmo-operator --timeout=240s
}

wait_for_pod() {
  local pod_name="$1"
  local retries=60
  echo -n "Waiting for pod ${pod_name} to appear and become Ready..."
  until kubectl get pod "${pod_name}" >/dev/null 2>&1; do
    sleep 1
    retries=$((retries - 1))
    if [ $retries -le 0 ]; then
      echo " TIMEOUT (Pod not created)"
      return 1
    fi
  done
  kubectl wait --for=condition=Ready "pod/${pod_name}" --timeout=360s
  echo " READY."
}

wait_for_tcp_port() {
  local host="$1"
  local port="$2"
  local retries="${3:-30}"
  echo -n "Waiting for TCP endpoint ${host}:${port}..."
  until python3 -c "import socket,sys; s=socket.socket(); s.settimeout(0.5); s.connect(('${host}', int('${port}'))); s.close()" >/dev/null 2>&1; do
    sleep 1
    retries=$((retries - 1))
    if [ $retries -le 0 ]; then
      echo " TIMEOUT (Unreachable)"
      return 1
    fi
  done
  echo " REACHABLE."
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
