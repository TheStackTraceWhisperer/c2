#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "=== Launching FFXI Desktop Client ==="
./mvnw exec:java -pl client-desktop -Dexec.mainClass="com.example.client.desktop.Launcher"
