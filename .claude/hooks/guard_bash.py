#!/usr/bin/env python3
"""PreToolUse guard for Bash: keep secrets out of the transcript and out of git."""
import json, sys, re, subprocess, os

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
cmd = (data.get("tool_input") or {}).get("command") or ""


def deny(reason):
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                             "permissionDecision": "deny",
                                             "permissionDecisionReason": reason}}))
    sys.exit(0)


if re.search(r"local\.properties", cmd) and not re.search(r"^\s*(ls|test|\[|stat)\b", cmd):
    deny("Commands that read or write local.properties are off-limits (it holds META_API_KEY). The user manages that file.")
if re.search(r"(^|[\s/])\.env(\.[A-Za-z0-9_-]+)?(\s|$)", cmd) and re.search(r"\b(cat|less|head|tail|sed|awk|grep|source)\b", cmd):
    deny("Do not read dotenv files; they hold secrets.")
if re.search(r"\bMETA_API_KEY\b", cmd) and re.search(r"\b(echo|printf|printenv|env)\b", cmd):
    deny("Never print META_API_KEY.")
if re.search(r"git\s+push\s+.*(--force|-f)\b", cmd) or re.search(r"git\s+reset\s+--hard", cmd):
    deny("Force-push / hard reset are blocked. Ask the user.")

# Secret scan on staged changes right before a commit
if re.search(r"\bgit\s+commit\b", cmd):
    root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    try:
        diff = subprocess.run(["git", "diff", "--cached", "-U0"], cwd=root,
                              capture_output=True, text=True, timeout=20).stdout
    except Exception:
        diff = ""
    added = "\n".join(l for l in diff.splitlines() if l.startswith("+") and not l.startswith("+++"))
    if re.search(r"META_API_KEY\s*=\s*['\"]?[A-Za-z0-9_\-]{16,}", added) or \
            re.search(r"\b(sk|key|tok|Bearer)[-_ ][A-Za-z0-9_\-]{24,}", added):
        deny("Staged diff looks like it contains an API key. Unstage it and redact before committing.")
    if re.search(r"^\+\+\+ b/local\.properties", diff, re.M):
        deny("local.properties is staged. It must never be committed.")
sys.exit(0)
