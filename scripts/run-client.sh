#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MAIN_CLASS="com.example.client.desktop.Launcher"
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    zone|viewer|--zone|--zone-viewer)
      MAIN_CLASS="com.ffxi.viewer.ZoneViewer"
      shift
      ;;
    char|character|--char|--character)
      MAIN_CLASS="com.ffxi.viewer.CharacterViewer"
      shift
      ;;
    glb|--glb)
      MAIN_CLASS="com.ffxi.viewer.GlbRenderer"
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

echo "=== Ensuring client-desktop and dependencies are compiled ==="
./mvnw test-compile -pl client-desktop -am -DskipTests -q

echo "=== Launching ${MAIN_CLASS} ${EXTRA_ARGS[*]:-} ==="
if [[ ${#EXTRA_ARGS[@]} -gt 0 ]]; then
  ./mvnw exec:exec -pl client-desktop -Dexec.mainClass="${MAIN_CLASS}" -Dexec.args="${EXTRA_ARGS[*]}"
else
  ./mvnw exec:exec -pl client-desktop -Dexec.mainClass="${MAIN_CLASS}"
fi
