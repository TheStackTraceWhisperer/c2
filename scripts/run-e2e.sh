#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

CLUSTER_NAME="mmo-cluster"
GATEWAY_PORT=8888
DB_PORT=5432
DASHBOARD_PORT=8443
CERTS_DIR="${ROOT_DIR}/target/certs"

echo "=== Kubernetes k3d E2E Cluster Harness ==="

# 1. Package container using Micronaut built-in Docker plugin
echo "[build] Packaging platform-modulith with Micronaut docker plugin..."
mvn clean package -DskipTests -Dpackaging=docker

# 2. Check k3d & kubectl
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

# 3. Create k3d cluster with exposed UDP Gateway port and TCP Postgres port
if ! k3d cluster list | grep -q "${CLUSTER_NAME}"; then
  echo "[k3d] Creating cluster '${CLUSTER_NAME}'..."
  k3d cluster create "${CLUSTER_NAME}" \
    --port "${GATEWAY_PORT}:${GATEWAY_PORT}/udp@loadbalancer" \
    --port "${DB_PORT}:${DB_PORT}@loadbalancer"
fi

# 4. Deploy Kubernetes Dashboard
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
kubectl port-forward --address 0.0.0.0 -n kubernetes-dashboard svc/kubernetes-dashboard "${DASHBOARD_PORT}:443" > /dev/null 2>&1 &
sleep 2

DASHBOARD_TOKEN=$(kubectl -n kubernetes-dashboard create token admin-user --duration=24h)

echo ""
echo "=========================================================================="
echo " Kubernetes Dashboard is live and accessible!"
echo " URL:   https://127.0.0.1:${DASHBOARD_PORT}"
echo " Token: ${DASHBOARD_TOKEN}"
echo "=========================================================================="
echo ""

# 5. Generate Real RSA 2048-bit Keypair using OpenSSL
echo "[crypto] Generating valid 2048-bit RSA keypair with OpenSSL..."
mkdir -p "${CERTS_DIR}"
openssl genpkey -algorithm RSA -out "${CERTS_DIR}/jwt.key" -pkeyopt rsa_keygen_bits:2048
openssl rsa -pubout -in "${CERTS_DIR}/jwt.key" -out "${CERTS_DIR}/jwt.pub"

kubectl create secret generic jwt-keypair-secret \
  --from-file=tls.key="${CERTS_DIR}/jwt.key" \
  --from-file=tls.crt="${CERTS_DIR}/jwt.pub" \
  --dry-run=client -o yaml | kubectl apply -f -

# 6. Import built image into k3d
echo "[k3d] Importing platform-modulith:latest into cluster..."
k3d image import platform-modulith:latest -c "${CLUSTER_NAME}"

function cleanup_on_exit {
  local exit_code=$?
  if [ $exit_code -ne 0 ]; then
    echo "[e2e] Test execution failed with exit code ${exit_code}. Dumping pod logs:"
    kubectl get pods -A || true
    echo "=== GATEWAY LOGS ==="
    kubectl logs deployment/gateway --tail=100 || true
    echo "=== LOGIN LOGS ==="
    kubectl logs deployment/login-service --tail=100 || true
    echo "=== LOBBY LOGS ==="
    kubectl logs deployment/lobby-service --tail=100 || true
    echo "=== OPERATOR LOGS ==="
    kubectl logs deployment/mmo-operator --tail=100 || true
  fi
}
trap cleanup_on_exit EXIT

# 7. Apply manifests & trigger rolling restart
echo "[k8s] Deploying PostgreSQL, Static Services, Operator, and Gateway..."
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

echo "[k8s] Waiting for MMO Operator to reconcile and spin up dynamic World & FFXI Zone pods..."
function wait_for_pod {
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

# 8. Run Integration Test Suite against live k3d endpoints
echo "[test] Executing Integration Test Suite (IT) against live k3d cluster..."
mvn test -pl platform-modulith -am \
  -Dtest=com.example.*IT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dapp.gateway.host=127.0.0.1 \
  -Dapp.gateway.port=${GATEWAY_PORT} \
  -Ddatasources.default.url="jdbc:postgresql://localhost:${DB_PORT}/mmo_platform" \
  -Ddatasources.default.maximum-pool-size=3 \
  -Ddatasources.default.minimum-idle=1 \
  -Dapp.auth.private-key-path="${CERTS_DIR}/jwt.key" \
  -Dapp.auth.public-key-path="${CERTS_DIR}/jwt.pub"

echo "[e2e] All FFXI multi-schema cluster tests passed successfully!"