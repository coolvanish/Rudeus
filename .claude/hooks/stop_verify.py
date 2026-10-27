#!/usr/bin/env python3
"""Stop hook: if source changed and scripts/verify.sh has not passed since, send Claude back once."""
import json, sys, os

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
if data.get("stop_hook_active"):
    sys.exit(0)  # already looped once; never block twice in a row
root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
dirty = os.path.join(root, ".gradle", ".dirty-since-verify")
if not os.path.exists(dirty):
    sys.exit(0)
files = sorted({l.split(" ", 1)[1].strip() for l in open(dirty) if " " in l})
shown = ", ".join(files[:12]) + (" ..." if len(files) > 12 else "")
print(json.dumps({
    "decision": "block",
    "reason": "Unverified source changes since the last scripts/verify.sh: " + shown +
              ". Run scripts/verify.sh (or ./gradlew :core:jvmTest for core-only changes, then scripts/verify.sh --mark) "
              "and report the result. If verify cannot run, say why explicitly."
}))
sys.exit(0)
