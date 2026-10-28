# T11 — drawings-restyle

status: doing
created: 2026-10-28
day: Day 2
gate: ≥7/10 pass gate first try · p50 < 40 s · ≤ $0.05/character
cut-if-slips: ship drawings with `gatePassed=false` (they still get a sprite and a sheet) and report the gate as FAIL for drawings

## Goal
Child drawings are the product's main input, and on live run 4 none of them passed the style gate. Make Muse redraw a
drawing in the refs' style (not hand it back) without losing what the child drew, and get one run of ≥ 10 real inputs.

## Evidence
- Live run 4 `runs/2026-10-28T07-46-08Z.json` (the user's run, 6 inputs; 3 webp photos were silently skipped):
  toy-car-01/02 passed first try (0.861, 0.814); all 4 drawings failed all 3 attempts (Spark 0.15–0.55). Muse
  returned the drawing almost unchanged: the child's own marker/crayon strokes and saturated colours. Gate: 2/6
  first try (UNTESTED, n=6), p50 107 s FAIL, $0.046/character PASS.
- Latency this morning is ~2× last night's: stylize 15–93 s, gate 8–33 s per call; the toy cars took 46–63 s with
  one attempt each (29 s last night). p50 < 40 s needs first-try passes *and* night-time latency.
- Scratch runs on the same 4 drawings (no recording):
  - prompt with "sticker" + "redraw from scratch": 0/4 (`2026-10-28T07-59-10Z`). Clean flat digital fills, and a
    die-cut white border with a grey shadow that the matte keeps.
  - current prompt (no "sticker", the refs' fine crayon texture, no border): 1/4 first try (`2026-10-28T08-08-21Z`,
    drawing_car_2 0.907). The ladybug now looks on-style but still scores 0.45; drawing_car still came back unchanged on
    one attempt.

## Done so far
- `Prompts.STYLIZE` + `docs/api-samples/muse-stylize/request.json.tmpl`: a drawing must be redrawn from scratch with the
  refs' outline, soft palette and crayon texture, keeping each part's colour; no border. `RequestShapeTest` green.
- `character --drawings` converts webp/HEIC/avif/gif/bmp/tiff inputs (`cli/.../ImageConvert.kt`: macOS `sips`, webp
  also `dwebp`; webp → PNG, the rest → JPEG to keep EXIF rotation) instead of skipping them silently. No new dependency:
  a JVM webp reader (TwelveMonkeys) would need a Gradle change. Offline smoke: a webp and a HEIC replayed end to end; a
  broken webp gives a clear error. `docs/drawings/.gitignore` ignores `*.webp`, `*.heic`, `*.heif`, `*.avif`.
- Inputs ready in scratch for the next run: `toy_dino.webp` → png, and `toy_dinos.webp` cropped into 4 single toys
  (black dino, two-headed dragon, cheetah, brachiosaurus). `toy_animals.webp` is left out: the 5 plush toys touch, so
  no crop holds one toy. With the 6 jpgs that makes 11 inputs.

## Open (user decisions)
- **Score rubric.** `Prompts.STYLE_SCORE` asks Spark for line weight, *palette* and shading. A ladybug has to stay red
  and black, the refs are pastel, so palette fights "keep the child's colours". The deterministic half already measures
  palette (ΔE, 0.4 × 0.7 of the score). Proposed: Spark rates technique only (outline weight and colour, crayon fill
  texture, shading, simple shapes) and ignores the subject and its colours. This changes what the gate measures.
- **The 11-input run** (≤ $0.90 worst case, `--budget-usd 1`), with `--record --force --bundle`.

## Result (filled on acceptance)
