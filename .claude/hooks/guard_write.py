#!/usr/bin/env python3
"""PreToolUse guard for Edit/Write: block secrets, ask for the hand-review zone, enforce module boundaries."""
import json, sys, re, os

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

tool_input = data.get("tool_input") or {}
path = tool_input.get("file_path") or ""
root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
rel = os.path.relpath(path, root) if os.path.isabs(path) else path
rel = rel.replace("\\", "/")


def out(decision, reason, ctx=None):
    o = {"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                "permissionDecision": decision,
                                "permissionDecisionReason": reason}}
    if ctx:
        o["hookSpecificOutput"]["additionalContext"] = ctx
    print(json.dumps(o))
    sys.exit(0)


SECRET_FILES = [r"^local\.properties$", r"(^|/)\.env(\..*)?$", r"\.(keystore|jks)$"]
if any(re.search(p, rel) for p in SECRET_FILES):
    out("deny", f"{rel} holds secrets or local machine config. The user edits it by hand (CLAUDE.md > Secrets).")

if rel.startswith("runs/") and rel.endswith(".json"):
    out("deny", "runs/*.json are produced by MetricsLog, never hand-edited (README numbers must match real runs).")

HAND_REVIEW = [r"\.gradle\.kts$", r"^gradle/libs\.versions\.toml$", r"^gradle\.properties$",
               r"^androidApp/src/main/.*\.(kt|xml)$"]
if any(re.search(p, rel) for p in HAND_REVIEW):
    out("ask", f"HAND-REVIEW zone: {rel}. AR/Filament/Gradle changes are approved by a human (CLAUDE.md).",
        "Keep this diff minimal and add a '// HAND-REVIEW:' comment stating the intent.")

content = tool_input.get("content") or tool_input.get("new_string") or ""
if re.search(r"META_API_KEY\s*=\s*['\"]?[A-Za-z0-9_\-]{20,}", content) or \
        re.search(r"\b(sk|key|tok)[-_][A-Za-z0-9]{24,}\b", content):
    out("deny", "The content looks like it embeds an API key. Keys live only in local.properties / env.")

AR_TYPES = r"^\s*import\s+(com\.google\.ar|io\.github\.sceneview|com\.google\.android\.filament)"
if rel.startswith("core/src/") and re.search(AR_TYPES, content, re.M):
    out("deny", ":core must not import ARCore, SceneView or Filament (CLAUDE.md boundaries).")
if rel.startswith(("core/src/commonMain/", "core/src/jvmMain/", "core/src/commonTest/")) and \
        re.search(r"^\s*import\s+(androidx\.|android\.)", content, re.M):
    out("deny", ":core common/jvm sources must not import Android or Compose. Only core/src/androidMain may use android.* (no UI).")
if rel.startswith("core/src/") and re.search(r"^\s*import\s+androidx\.compose", content, re.M):
    out("deny", ":core must not import Compose anywhere (CLAUDE.md boundaries).")

FORBIDDEN_IN_SHARED = r"^\s*import\s+(com\.google\.ar|io\.github\.sceneview|com\.google\.android\.filament)"
if rel.startswith("shared/src/") and re.search(FORBIDDEN_IN_SHARED, content, re.M):
    out("deny", ":shared must not import ARCore/SceneView/Filament. The AR view is a slot composable from :androidApp.")

sys.exit(0)
