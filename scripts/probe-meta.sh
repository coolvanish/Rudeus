#!/usr/bin/env bash
# Day-1 API probe. Runs ONE request against the Meta model API and saves a redacted response
# next to the input, under docs/api-samples/<job>/. Reads META_API_KEY from local.properties or the
# environment and never prints it. Run it yourself; agents are blocked from touching local.properties.
#
#   scripts/probe-meta.sh <job> <input-file> [extra curl args...]
#   jobs: sam-cutout | sam-table | sam-rig | muse-stylize | spark-score | spark-sheet | voice
#
# The request path and body per job live in docs/api-samples/<job>/request.json.tmpl (you fill them in
# from the API docs once; keep {{INPUT_B64}} and {{PROMPTS}} placeholders). META_BASE_URL is required.
set -euo pipefail
cd "$(dirname "$0")/.."
job="${1:?job}"; input="${2:?input file}"; shift 2 || true
dir="docs/api-samples/$job"; mkdir -p "$dir"
tmpl="$dir/request.json.tmpl"
[ -f "$tmpl" ] || { echo "missing $tmpl: write the request body template first (see docs/api-samples/README.md)" >&2; exit 2; }
: "${META_BASE_URL:?set META_BASE_URL (no trailing slash)}"
path=$(sed -n 's/^# path: //p' "$tmpl" | head -1); : "${path:?first line of $tmpl must be '# path: /v1/...'}"

key="${META_API_KEY:-}"
if [ -z "$key" ] && [ -f local.properties ]; then
  key=$(grep -E '^META_API_KEY=' local.properties | cut -d= -f2- | tr -d '\r')
fi
[ -n "$key" ] || { echo "META_API_KEY not found (local.properties or env)" >&2; exit 2; }

name=$(basename "${input%.*}")
b64=$(base64 < "$input" | tr -d '\n')
body=$(sed -e '/^# path: /d' "$tmpl" | python3 -I -c '
import sys, json
b64 = sys.argv[1]; prompts = json.dumps(["cup","book","phone","bottle","hand","object"])
print(sys.stdin.read().replace("{{INPUT_B64}}", b64).replace("{{PROMPTS}}", prompts))' "$b64")

raw="$dir/$name.response.json"; hdr="$dir/$name.headers.txt"
start=$(python3 -I -c 'import time; print(int(time.time()*1000))')
code=$(printf '%s' "$body" | curl -sS -o "$raw" -D "$hdr" -w '%{http_code}' \
  -H "Authorization: Bearer $key" -H "Content-Type: application/json" \
  "$@" -X POST "$META_BASE_URL$path" --data-binary @-)
end=$(python3 -I -c 'import time; print(int(time.time()*1000))')
unset key b64 body

# Redact: strip auth-looking headers, shrink base64 blobs, record latency.
python3 -I - "$raw" "$hdr" "$dir/meta.json" "$job" "$path" "$((end - start))" "$code" <<'PY'
import sys, json, re, os, datetime
raw, hdr, meta, job, path, ms, code = sys.argv[1:]
txt = open(raw, encoding="utf-8", errors="replace").read()
try:
    obj = json.loads(txt)
    def scrub(x):
        if isinstance(x, dict): return {k: ("<redacted>" if re.search(r"(auth|key|token|secret)", k, re.I) else scrub(v)) for k, v in x.items()}
        if isinstance(x, list): return [scrub(v) for v in x]
        if isinstance(x, str) and len(x) > 2048 and re.fullmatch(r"[A-Za-z0-9+/=\n]+", x): return f"<base64 {len(x)} chars>"
        return x
    open(raw, "w").write(json.dumps(scrub(obj), indent=2, ensure_ascii=False))
except Exception:
    pass
lines = [l for l in open(hdr, errors="replace").read().splitlines() if not re.match(r"(?i)^(authorization|x-api-key|set-cookie|cookie)", l)]
open(hdr, "w").write("\n".join(lines) + "\n")
m = json.load(open(meta)) if os.path.exists(meta) else {"job": job, "probes": []}
m["job"] = job; m["path"] = path
m.setdefault("probes", []).append({"when": datetime.datetime.utcnow().isoformat() + "Z", "input": os.path.basename(raw).replace(".response.json", ""), "httpStatus": int(code), "latencyMs": int(ms)})
m["latencyMs"] = int(sum(p["latencyMs"] for p in m["probes"]) / len(m["probes"]))
json.dump(m, open(meta, "w"), indent=2)
print(f"{job}: HTTP {code} in {ms} ms -> {raw} (redacted), latency recorded in {meta}")
PY
