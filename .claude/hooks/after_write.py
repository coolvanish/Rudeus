#!/usr/bin/env python3
"""PostToolUse: remember that source changed since the last verify, so the Stop hook can nag."""
import json, sys, os, re, time

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
path = (data.get("tool_input") or {}).get("file_path") or ""
root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
rel = os.path.relpath(path, root).replace("\\", "/") if os.path.isabs(path) else path
if re.search(r"^(core|shared|androidApp|cli)/.*\.(kt|kts|xml)$", rel) or \
        rel.endswith(".gradle.kts") or rel.endswith("libs.versions.toml"):
    os.makedirs(os.path.join(root, ".gradle"), exist_ok=True)
    with open(os.path.join(root, ".gradle", ".dirty-since-verify"), "a") as f:
        f.write(f"{int(time.time())} {rel}\n")
sys.exit(0)
