# docs/drawings — real inputs for the Day 2 live run

Put the photos of real drawings and toys here, one character per file. `character --drawings docs/drawings` takes
every `.png`, `.jpg` and `.jpeg` in this folder (sorted by name). `.webp`, `.heic`/`.heif`, `.avif`, `.gif`, `.bmp`
and `.tiff` are converted first with macOS `sips` (webp also with `dwebp`, `brew install webp`): webp to PNG, the
rest to JPEG so an iPhone photo keeps its rotation. Other files (like this README) are ignored.

## Naming

The file name without its extension is the character's `<ref>`: it names the fixtures, the bundle folder and
`characters[].input` in `runs/*.json`. Use kebab-case, no spaces, a number suffix:

```
cat-01.jpg   dragon-02.jpg   toy-car-01.jpg   robot-toy-02.png
```

Never reuse `sample-01` / `sample-02`: those are the synthetic fixtures the tests rely on, and `--record` refuses to
overwrite them. Reusing a ref that is already recorded (e.g. `drawing-01`) needs `--force`.

## What may go in

Only our own drawings and generic, unbranded toys (no licensed characters, no logos, no people's faces). Photograph
on a plain background, the whole character in frame, daylight. Aim for 10 inputs: the Day 2 gate needs n >= 10.

## Style refs

Done: `docs/style-refs/` holds 8 provisional refs (Muse-generated, culled from 12; see `docs/style-refs/README.md`
for how to replace them). `--refs docs/style-refs` sends every image directly in that folder.

## Morning of Oct 28: finish the Day 2 gate

The gate needs n >= 10 distinct drawings/toys **in one run**. Three are already here (`watermelon-01.jpg`,
`toy-car-01.jpg`, `toy-car-02.jpg`, recorded in live run 2); add 7 more photos, then run the command below with
`--force` (it re-records those three, ~$0.09). Do not name a new photo `drawing-01`, `sample-01` or `sample-02`:
tests depend on those fixtures. Photos in this folder are not committed (`.gitignore`); `--record` keeps a copy as
`docs/api-samples/sam-cutout/<ref>.input.<ext>`.

## The live run

The key comes from the `META_API_KEY` environment variable or the repo-root `local.properties`; the CLI reads it
in process and never prints it. Default budget is $1.00; the run stops starting characters before the next one
could exceed it, still writes the run JSON, and exits 1.

```
./gradlew :cli:run --args="character --live --drawings docs/drawings --refs docs/style-refs --record docs/api-samples --force --bundle docs/bundled --budget-usd 1 --out runs"
./gradlew :cli:run --args="metrics gate runs/<runId>.json"
```

Outputs:
- `runs/<runId>.json` — the measured run (README numbers come only from these files).
- `docs/api-samples/<job>/<ref>.fixture.json` + `muse-stylize/<ref>.out-<n>.png` + `sam-cutout/<ref>.input.<ext>`
  — recorded replies that replay offline: `character --fixtures docs/api-samples --ref <ref> --out runs`.
- `docs/bundled/<ref>/sprite.png`, `sheet.json` and `docs/bundled/index.json` — the demo characters the app ships.

`--record` checks every target before the first API call: it refuses synthetic fixtures always, and existing real
recordings unless `--force`. It only works with `--live` (offline, the replies already are fixtures).
