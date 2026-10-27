#!/usr/bin/env bash
# Pixel helpers over adb. Usage: scripts/device.sh run|log|shot <file>|pull-runs|arcore
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb
PKG=kz.rudy.ar

case "${1:-}" in
  run)
    "$ADB" shell am start -n "$PKG/.MainActivity" ;;
  log)
    "$ADB" logcat -c
    "$ADB" logcat -v time ARCore:I ARCore-Session:I Filament:W SceneView:D "$PKG":D AndroidRuntime:E '*:S' ;;
  shot)
    out="${2:-shot-$(date +%H%M%S).png}"
    "$ADB" exec-out screencap -p > "$out" && echo "$out" ;;
  pull-runs)
    mkdir -p runs
    "$ADB" shell "run-as $PKG ls files/runs" 2>/dev/null | while read -r f; do
      "$ADB" shell "run-as $PKG cat files/runs/$f" > "runs/$f" && echo "pulled runs/$f"
    done ;;
  arcore)
    "$ADB" shell dumpsys package com.google.ar.core | grep -E 'versionName' | head -1 ;;
  *)
    echo "usage: scripts/device.sh run|log|shot [file]|pull-runs|arcore" >&2; exit 2 ;;
esac
