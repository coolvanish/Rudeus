#!/usr/bin/env bash
# Create an ai/tasks brief from the template.  scripts/new-task.sh T07 occluder-boxes
set -euo pipefail
cd "$(dirname "$0")/.."
id="${1:?task id like T07}"; slug="${2:?slug}"
f="ai/tasks/$id-$slug.md"
[ -e "$f" ] && { echo "$f exists" >&2; exit 1; }
sed -e "s/{{ID}}/$id/g" -e "s/{{SLUG}}/$slug/g" -e "s/{{DATE}}/$(date +%Y-%m-%d)/g" ai/tasks/TEMPLATE.md > "$f"
echo "$f"
