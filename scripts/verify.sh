#!/usr/bin/env bash
# Definition of done. Exit 0 == every gate below passed.
#   scripts/verify.sh            full: core tests, shared host test, cli compile, Android debug build
#   scripts/verify.sh --quick    core tests only (does not clear the dirty stamp)
#   scripts/verify.sh --mark     clear the dirty stamp after a quick run you trust (core-only change)
set -euo pipefail
cd "$(dirname "$0")/.."
STAMP=".gradle/.dirty-since-verify"

case "${1:-}" in
  --quick)
    ./gradlew :core:jvmTest --console=plain -q
    echo "verify: core tests OK (quick; stamp not cleared)"; exit 0 ;;
  --mark)
    rm -f "$STAMP"; echo "verify: stamp cleared"; exit 0 ;;
esac

start=$(date +%s)
./gradlew :core:jvmTest :shared:testAndroidHostTest :cli:compileKotlin :androidApp:assembleDebug --console=plain -q
end=$(date +%s)

summ() { grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$1"/*.xml 2>/dev/null | \
  awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {printf "%d tests, %d failed, %d errors, %d skipped", t, f, e, s}'; }
echo "verify: core     -> $(summ core/build/test-results/jvmTest)"
echo "verify: shared   -> $(summ shared/build/test-results/testAndroidHostTest)"
apk=$(ls androidApp/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
echo "verify: apk      -> ${apk:-missing} ($(du -h "$apk" 2>/dev/null | cut -f1))"
echo "verify: OK in $((end - start)) s"
rm -f "$STAMP"
