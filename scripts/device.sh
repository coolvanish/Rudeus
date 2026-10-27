#!/usr/bin/env bash
# Pixel helpers over adb. Usage: scripts/device.sh run|log|shot <file>|pull-runs|pull-recordings|arcore
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb
PKG=kz.rudy.ar

case "${1:-}" in
  run)
    # Cold start so the one-shot RudeusGate lines (session_resumed, plane_detected_ms, depth_*) are logged again.
    "$ADB" shell am force-stop "$PKG"
    "$ADB" shell am start -n "$PKG/.MainActivity" ;;
  log)
    # No `logcat -c`: the gate probe logs plane/depth once at startup and clearing would drop those lines.
    # Start this first, then `scripts/device.sh run` in another terminal.
    "$ADB" logcat -v time RudeusGate:I ARCore:I ARCore-Session:I Filament:W SceneView:D "$PKG":D AndroidRuntime:E '*:S' ;;
  shot)
    out="${2:-shot-$(date +%H%M%S).png}"
    "$ADB" exec-out screencap -p > "$out" && echo "$out" ;;
  pull-runs)
    mkdir -p runs
    "$ADB" shell "run-as $PKG ls files/runs" 2>/dev/null | while read -r f; do
      "$ADB" shell "run-as $PKG cat files/runs/$f" > "runs/$f" && echo "pulled runs/$f"
    done ;;
  pull-recordings)
    # ARCore Recording API MP4s written by the in-app Rec button (getExternalFilesDir(null)/recordings).
    mkdir -p docs/recordings
    rec="/sdcard/Android/data/$PKG/files/recordings"
    "$ADB" shell "ls $rec" 2>/dev/null | while read -r f; do
      "$ADB" pull "$rec/$f" "docs/recordings/$f" >/dev/null && echo "pulled docs/recordings/$f"
    done ;;
  arcore)
    "$ADB" shell dumpsys package com.google.ar.core | grep -E 'versionName' | head -1 ;;
  *)
    echo "usage: scripts/device.sh run|log|shot [file]|pull-runs|pull-recordings|arcore" >&2; exit 2 ;;
esac
