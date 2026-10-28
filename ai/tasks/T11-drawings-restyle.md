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

- Colour-free rescore (user decision 2026-10-28): an attempt below 0.7 is scored again on the same sprite with
  `Prompts.STYLE_SCORE_NO_COLOUR` + an outline-only deterministic half; the higher score counts (stage `rescore`,
  fixtures `techniqueScores`). Commit 4f701e6, review: merge (latency +8–25 s per failed attempt; "first try" now
  includes rescore passes; outline-only half is close to 1, so the rescore passes from Spark ≈ 0.5).

## Live run 5 `runs/2026-10-28T09-11-21Z.json` (15 inputs, new prompt + rescore, $0.77)
- Gate: first try 6/15 = 40 % FAIL; p50 116.6 s FAIL; $0.0515/character FAIL (just over). Passed at all: 10/15.
- Toys 5/7 first try (56–72 s each); drawings 1/8 first try, 5/8 in the end. 4 of the 10 passes came from the rescore
  (drawing_penguini on attempt 1).
- Failures: uncoloured subjects (drawing_cowcow and horse are pencil/line drawings, toy_fox a white plush: Spark
  0.1–0.4 on both rubrics), drawing_watermelon 0.681 (near miss), toy-car-02 0.35–0.65 for a sprite that looks
  like the one Spark scored 0.88–0.95 last night (score noise).
- Latency is mostly the provider today: a single-attempt toy takes 56–72 s (stylize 26–51 s, score 16–25 s) against
  29 s last night, so p50 < 40 s is out of reach this morning even at 100 % first try.
- `thunder.webp` (Lightning McQueen, licensed) left out of the run.

## Speed pass (user decision 2026-10-28)
- Commit 13b3be9 (+ ab0879b review follow-ups): both gate looks run in parallel on every attempt; Spark gets 3 style
  refs (`SPARK_MAX_REFS`, cull.json order: camel, yurt, snow leopard); the colour-free call meters under
  `Jobs.SPARK_SCORE_TECHNIQUE`. Review: merge. Known: a look that throws is not recorded in the fixture lists, so a
  replay can drift from the live run; three Spark calls are in flight at once (no 429 backoff).
- Probe `toy_dino` 10:09Z: first try 39.4 s, $0.0284 (run 5: 56.3 s, $0.0289); gate 11.7 s ‖ rescore 9.3 s, stylize
  22.8 s.
- Re-measure is scheduled (scratchpad `remeasure/remeasure.sh`, frozen CLI build): a toy probe every 90 min, at most
  10 (≤ $0.30); the first probe ≤ 32 s triggers one run of the same 15 inputs with record + bundle, $1 cap.

## Open (user decisions)
- Day 2 gate go/no-go after run 5 (all three rows FAIL).
- Which inputs may be committed and bundled: several drawings look like pictures from the internet (`horse.jpeg` carries
  an artist's signature); their input copies (`sam-cutout/<ref>.input.*`) and sprites would land in a public repo and
  the app. Run 5's fixtures and bundle are uncommitted until that is answered.

## Result (filled on acceptance)
