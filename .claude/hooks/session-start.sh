#!/usr/bin/env bash
# SessionStart hook: give every session the same short situational brief.
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
deadline="2026-11-04"
today=$(date +%Y-%m-%d)
to_epoch() { date -j -f %Y-%m-%d "$1" +%s 2>/dev/null || date -d "$1" +%s; }
days_left=$(( ( $(to_epoch "$deadline") - $(to_epoch "$today") ) / 86400 ))
echo "## Rudeus session brief ($today) — $days_left day(s) to the Nov 4 deadline"
if [ -d .git ]; then
  echo "- git: $(git branch --show-current 2>/dev/null || echo '?') · $(git status --porcelain | wc -l | tr -d ' ') uncommitted path(s) · last: $(git log -1 --format='%h %s' 2>/dev/null || echo 'no commits')"
else
  echo "- git: NOT INITIALISED (Day 1 item)"
fi
if grep -q '^META_API_KEY=.\+' local.properties 2>/dev/null; then
  echo "- META_API_KEY: present in local.properties"
else
  echo "- META_API_KEY: MISSING in local.properties (user adds META_API_KEY=... ; never commit)"
fi
if command -v adb >/dev/null 2>&1; then
  n=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  echo "- adb: $n device(s) attached"
fi
last_run=$(ls -t runs/*.json 2>/dev/null | head -1)
if [ -n "$last_run" ]; then echo "- last metrics run: $last_run"; else echo "- metrics: no runs yet (runs/*.json)"; fi
nsamples=$(find docs/api-samples -name '*.json' 2>/dev/null | wc -l | tr -d ' ')
echo "- api fixtures: $nsamples json file(s) in docs/api-samples"
echo "- plan: ai/build-plan.md · open briefs: $(ls ai/tasks/T*.md 2>/dev/null | wc -l | tr -d ' ')"
echo "- DoD: scripts/verify.sh must pass before you stop."
exit 0
