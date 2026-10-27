#!/usr/bin/env bash
# Day-1 API probe. Runs ONE job against the Meta Model API and saves redacted responses
# next to the input, under docs/api-samples/<job>/. Reads META_API_KEY from local.properties or the
# environment and never prints it. Run it yourself; agents are blocked from touching local.properties.
#
#   scripts/probe-meta.sh [--dry-run] <job> <input-file> [extra curl args...]
#   jobs: sam-cutout | sam-table | sam-rig | muse-stylize | spark-score | spark-sheet | voice
#
#   --dry-run   render the request(s) and print path, content type, byte size and the first 300 chars
#               (base64 collapsed). Needs neither META_BASE_URL nor the key. Exits 0.
#
# The request path and body per job live in docs/api-samples/<job>/request.json.tmpl. Header lines
# (all parsed here, then stripped before rendering):
#   # path: /v1/...                 required; appended to META_BASE_URL (no trailing slash, no /v1)
#   # content-type: json|multipart  default json. multipart: body is a flat JSON object of form fields;
#                                   object/array values are sent as JSON parts (;type=application/json)
#   # file-field: audio             multipart only: form field that carries <input-file>
#   # file-type: audio/wav          multipart only: MIME of that part
#   # accept: application/json      optional Accept header
#   # per-prompt: true              render once per prompt in {{PROMPTS}} (SAM 3.1 = one concept per request);
#                                   responses are saved as <name>.<prompt>.response.json
#   # base-url:, # model:, # unverified:   informational
# Placeholders: {{INPUT_B64}} {{INPUT_MIME}} {{INPUT_TEXT}} (JSON-escaped file text, no quotes)
#   {{PROMPTS}} (JSON array)  {{PROMPT}} (one prompt, JSON-escaped, no quotes; per-prompt templates)
#   {{REF_IMAGE_ITEMS}} (", {"image_url": "data:..."}" per muse-stylize/refs/* image, <= 9, for images/edits)
#   {{REF_CHAT_PARTS}}  (", {"type":"image_url","image_url":{"url":"data:..."}}" per ref, for chat/completions)
# Prompts come from docs/api-samples/<job>/prompts.json when it exists, else a per-job default.
set -euo pipefail
cd "$(dirname "$0")/.."
dry=0
if [ "${1:-}" = "--dry-run" ]; then dry=1; shift; fi
job="${1:?job}"; input="${2:?input file}"; shift 2 || true
dir="docs/api-samples/$job"; mkdir -p "$dir"
tmpl="$dir/request.json.tmpl"
[ -f "$tmpl" ] || { echo "missing $tmpl: write the request body template first (see docs/api-samples/README.md)" >&2; exit 2; }
[ -f "$input" ] || { echo "input file not found: $input" >&2; exit 2; }
path=$(sed -n 's/^# path: //p' "$tmpl" | head -1); : "${path:?first line of $tmpl must be '# path: /v1/...'}"
ctype=$(sed -n 's/^# content-type: //p' "$tmpl" | head -1); ctype="${ctype:-json}"
accept=$(sed -n 's/^# accept: //p' "$tmpl" | head -1)
prompts_file="$dir/prompts.json"; [ -f "$prompts_file" ] || prompts_file=""
name=$(basename "${input%.*}")

# Render every request body into a scratch dir (python reads the files itself: a real photo's base64
# does not fit in argv). manifest.tsv: <label>\t<curl-args-file>; each curl-args file holds one argument per line.
work=$(mktemp -d "${TMPDIR:-/tmp}/probe-meta.XXXXXX"); trap 'rm -rf "$work"' EXIT
python3 -I - "$tmpl" "$input" "$job" "$prompts_file" "docs/api-samples/muse-stylize/refs" "$work" "$dry" <<'PY'
import sys, json, re, os, base64, mimetypes
tmpl, inp, job, prompts_file, refs_dir, work, dry = sys.argv[1:]
dry = dry == "1"
lines = open(tmpl, encoding="utf-8").read().splitlines()
hdr = {}
body_lines = []
for l in lines:
    m = re.match(r"^# ([a-z-]+): ?(.*)$", l)
    if m and not body_lines:
        hdr.setdefault(m.group(1), m.group(2).strip())
    elif l.startswith("#") and not body_lines:
        continue
    else:
        body_lines.append(l)
template = "\n".join(body_lines).strip() + "\n"
path = hdr.get("path", ""); ctype = hdr.get("content-type", "json"); per_prompt = hdr.get("per-prompt", "").lower() == "true"

DEFAULT_PROMPTS = {
    "sam-cutout": ["drawing", "toy"],
    "sam-rig": ["head", "arm", "leg", "wing", "tail", "body"],
    "sam-table": ["cup", "book", "phone", "bottle", "hand", "object"],
}
prompts = json.load(open(prompts_file)) if prompts_file else DEFAULT_PROMPTS.get(job, ["object"])
if not isinstance(prompts, list) or not all(isinstance(p, str) for p in prompts):
    sys.exit(f"{prompts_file}: must be a JSON array of strings")

raw = open(inp, "rb").read()
b64 = base64.b64encode(raw).decode("ascii")
mime = mimetypes.guess_type(inp)[0] or "application/octet-stream"
if mime == "audio/x-wav": mime = "audio/wav"
try:
    text = raw.decode("utf-8").strip()
except UnicodeDecodeError:
    text = ""

def data_url(path):
    m = mimetypes.guess_type(path)[0] or "image/png"
    return f"data:{m};base64," + base64.b64encode(open(path, "rb").read()).decode("ascii")
refs = []
if os.path.isdir(refs_dir):
    refs = sorted(f for f in os.listdir(refs_dir) if f.lower().endswith((".png", ".jpg", ".jpeg", ".webp")))[:9]
ref_urls = [data_url(os.path.join(refs_dir, f)) for f in refs]
ref_image_items = "".join(", " + json.dumps({"image_url": u}) for u in ref_urls)
ref_chat_parts = "".join(", " + json.dumps({"type": "image_url", "image_url": {"url": u}}) for u in ref_urls)

def render(prompt):
    out = template
    out = out.replace("{{INPUT_B64}}", b64).replace("{{INPUT_MIME}}", mime)
    out = out.replace("{{INPUT_TEXT}}", json.dumps(text)[1:-1])
    out = out.replace("{{PROMPTS}}", json.dumps(prompts, ensure_ascii=False))
    out = out.replace("{{PROMPT}}", json.dumps(prompt, ensure_ascii=False)[1:-1])
    out = out.replace("{{REF_IMAGE_ITEMS}}", ref_image_items).replace("{{REF_CHAT_PARTS}}", ref_chat_parts)
    return out

def collapse(s):
    return re.sub(r"[A-Za-z0-9+/=]{200,}", lambda m: f"<b64 {len(m.group(0))} chars>", s)

name = os.path.splitext(os.path.basename(inp))[0]
labels = [(f"{name}.{re.sub(r'[^A-Za-z0-9_-]+', '_', p)}", p) for p in prompts] if per_prompt else [(name, prompts[0] if prompts else "")]
manifest = []
for i, (label, prompt) in enumerate(labels):
    body = render(prompt)
    args = []
    if hdr.get("accept"):
        args += ["-H", "Accept: " + hdr["accept"]]
    if ctype == "json":
        obj = json.loads(body)  # fail loudly if the rendered body is not valid JSON
        bf = os.path.join(work, f"{i}.body.json"); open(bf, "w", encoding="utf-8").write(body)
        args += ["-H", "Content-Type: application/json", "--data-binary", "@" + bf]
        if dry:
            print(f"[dry-run] {job} {label}: POST {path}  content-type=json  {len(body.encode())} bytes")
            print("  " + collapse(body)[:300].replace("\n", "\n  "))
    elif ctype == "multipart":
        fields = json.loads(body)
        if not isinstance(fields, dict): sys.exit(f"{tmpl}: multipart body must be a flat JSON object of form fields")
        ff = hdr.get("file-field") or sys.exit(f"{tmpl}: multipart template needs '# file-field: <name>'")
        ftype = hdr.get("file-type", mime)
        summary = []
        for k, v in fields.items():
            if isinstance(v, (dict, list)):
                val, ptype = json.dumps(v, ensure_ascii=False), "application/json"
            else:
                val, ptype = str(v), "text/plain"
            pf = os.path.join(work, f"{i}.{k}.part"); open(pf, "w", encoding="utf-8").write(val)
            args += ["-F", f'{k}=<"{pf}";type={ptype}']
            summary.append(f"  {k} ({ptype}, {len(val.encode())} bytes): {collapse(val)[:300]}")
        args += ["-F", f'{ff}=@"{os.path.abspath(inp)}";type={ftype}']
        summary.append(f"  {ff} (file, {ftype}, {len(raw)} bytes): @{inp}")
        if dry:
            print(f"[dry-run] {job} {label}: POST {path}  content-type=multipart  {len(fields) + 1} fields")
            print("\n".join(summary))
    else:
        sys.exit(f"{tmpl}: unknown content-type '{ctype}' (json|multipart)")
    af = os.path.join(work, f"{i}.curlargs"); open(af, "w", encoding="utf-8").write("\n".join(args) + "\n")
    manifest.append(f"{label}\t{af}")
open(os.path.join(work, "manifest.tsv"), "w").write("\n".join(manifest) + "\n")
if dry and len(labels) > 1:
    print(f"[dry-run] {job}: {len(labels)} requests (one per prompt): {', '.join(p for _, p in labels)}")
PY
[ "$dry" = 1 ] && exit 0

: "${META_BASE_URL:?set META_BASE_URL (no trailing slash, without /v1; the template path carries /v1)}"
key="${META_API_KEY:-}"
if [ -z "$key" ] && [ -f local.properties ]; then
  key=$(grep -E '^META_API_KEY=' local.properties | cut -d= -f2- | tr -d '\r')
fi
[ -n "$key" ] || { echo "META_API_KEY not found (local.properties or env)" >&2; exit 2; }

while IFS=$'\t' read -r label argsfile; do
  [ -n "$label" ] || continue
  cargs=(); while IFS= read -r l; do cargs+=("$l"); done < "$argsfile"
  raw="$dir/$label.response.json"; hdr="$dir/$label.headers.txt"
  start=$(python3 -I -c 'import time; print(int(time.time()*1000))')
  code=$(curl -sS -o "$raw" -D "$hdr" -w '%{http_code}' \
    -H "Authorization: Bearer $key" "${cargs[@]}" "$@" -X POST "$META_BASE_URL$path")
  end=$(python3 -I -c 'import time; print(int(time.time()*1000))')

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
done < "$work/manifest.tsv"
unset key
