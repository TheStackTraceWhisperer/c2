#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MAIN_CLASS="com.example.client.desktop.Launcher"
# Default: the shipped game client (client-desktop). Developer tools live in ffxi-tools.
MODULE="client-desktop"
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    zone|viewer|--zone|--zone-viewer)
      MAIN_CLASS="com.ffxi.viewer.ZoneViewer"
      MODULE="ffxi-tools"
      shift
      ;;
    sandbox|shader|--sandbox|--shader)
      MAIN_CLASS="com.ffxi.viewer.MapShaderSandbox"
      MODULE="ffxi-tools"
      shift
      ;;
    char|character|--char|--character)
      MAIN_CLASS="com.ffxi.viewer.CharacterViewer"
      MODULE="ffxi-tools"
      shift
      ;;
    glb|--glb)
      MAIN_CLASS="com.ffxi.viewer.GlbRenderer"
      MODULE="ffxi-tools"
      shift
      ;;
    com.ffxi.viewer.*)
      MAIN_CLASS="$1"
      MODULE="ffxi-tools"
      shift
      ;;
    com.*)
      MAIN_CLASS="$1"
      shift
      ;;
    *)
      EXTRA_ARGS+=("$1")
      shift
      ;;
  esac
done

echo "=== Ensuring ${MODULE} and dependencies are compiled ==="
./mvnw test-compile -pl "${MODULE}" -am -DskipTests -q

echo "=== Launching ${MAIN_CLASS} ${EXTRA_ARGS[*]:-} (module: ${MODULE}) ==="
./mvnw exec:exec -pl "${MODULE}" \
  -Dexec.executable=java \
  -Dexec.args="-classpath %classpath ${MAIN_CLASS} ${EXTRA_ARGS[*]:-}"
