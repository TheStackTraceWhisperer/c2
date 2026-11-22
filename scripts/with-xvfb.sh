#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <command> [args...]" >&2
    exit 1
fi

export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/xdg-runtime-$(id -u)}"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"

START_DISPLAY="${XVFB_DISPLAY:-99}"
START_DISPLAY="${START_DISPLAY#:}"
DISPLAY_NUM=""

for ((num = START_DISPLAY; num <= 120; num++)); do
    if [[ ! -e "/tmp/.X11-unix/X${num}" && ! -e "/tmp/.X${num}-lock" ]]; then
        DISPLAY_NUM="$num"
        break
    fi
done

if [[ -z "$DISPLAY_NUM" ]]; then
    echo "with-xvfb: no free X display found between :${START_DISPLAY} and :120" >&2
    exit 1
fi

XVFB_LOG="$(mktemp -t xvfb-${DISPLAY_NUM}.XXXXXX.log)"
XVFB_PID=""

cleanup() {
    local exit_code=$?
    if [[ -n "$XVFB_PID" ]] && kill -0 "$XVFB_PID" 2>/dev/null; then
        kill "$XVFB_PID" 2>/dev/null || true
        wait "$XVFB_PID" 2>/dev/null || true
    fi
    rm -f "$XVFB_LOG"
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

Xvfb ":${DISPLAY_NUM}" -screen 0 1920x1080x24 -ac +extension GLX +render -noreset -nolisten tcp >"$XVFB_LOG" 2>&1 &
XVFB_PID=$!

SOCKET_PATH="/tmp/.X11-unix/X${DISPLAY_NUM}"
READY=0
for ((i = 0; i < 100; i++)); do
    if ! kill -0 "$XVFB_PID" 2>/dev/null; then
        echo "with-xvfb: Xvfb exited unexpectedly on :${DISPLAY_NUM}" >&2
        cat "$XVFB_LOG" >&2 || true
        exit 1
    fi
    if [[ -S "$SOCKET_PATH" ]]; then
        READY=1
        break
    fi
    sleep 0.05
done

if [[ "$READY" -ne 1 ]]; then
    echo "with-xvfb: timed out waiting for Xvfb socket $SOCKET_PATH" >&2
    cat "$XVFB_LOG" >&2 || true
    exit 1
fi

export DISPLAY=":${DISPLAY_NUM}"
export MESA_GL_VERSION_OVERRIDE=4.6
export MESA_GLSL_VERSION_OVERRIDE=460
export LIBGL_ALWAYS_SOFTWARE=1

"$@"
rc=$?
exit "$rc"
