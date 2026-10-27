# docs/drawings — real inputs for the Day 2 live run

Put the photos of real drawings and toys here, one character per file. `character --drawings docs/drawings` takes
every `.png`, `.jpg` and `.jpeg` in this folder (sorted by name); other files (like this README) are ignored.

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

## Style refs first

```
./gradlew :cli:run --args="refs generate --prompt @docs/style-refs/style-prompt.txt --n 10 --out docs/style-refs/candidates --budget-usd 0.10"
./gradlew :cli:run --args="refs cull --from docs/style-refs/candidates --keep 8 --out docs/style-refs"
```

Write the style description into `docs/style-refs/style-prompt.txt` first (or pass `--prompt "<text>"`).
`refs generate` costs $0.01 per image and appends the prompt to `candidates/prompt.txt`; `refs cull` copies the 8 most
consistent into `docs/style-refs` and writes `docs/style-refs/cull.json`. `--refs docs/style-refs` sends every image
directly in that folder (not the `candidates/` subfolder).

## The live run

The key comes from the `META_API_KEY` environment variable or the repo-root `local.properties`; the CLI reads it
in process and never prints it. Default budget is $1.00; the run stops starting characters before the next one
could exceed it, still writes the run JSON, and exits 1.

```
./gradlew :cli:run --args="character --live --drawings docs/drawings --refs docs/style-refs --record docs/api-samples --bundle docs/bundled --budget-usd 1 --out runs"
./gradlew :cli:run --args="metrics gate runs/<runId>.json"
```

Outputs:
- `runs/<runId>.json` — the measured run (README numbers come only from these files).
- `docs/api-samples/<job>/<ref>.fixture.json` + `muse-stylize/<ref>.out-<n>.png` + `sam-cutout/<ref>.input.<ext>`
  — recorded replies that replay offline: `character --fixtures docs/api-samples --ref <ref> --out runs`.
- `docs/bundled/<ref>/sprite.png`, `sheet.json` and `docs/bundled/index.json` — the demo characters the app ships.

`--record` checks every target before the first API call: it refuses synthetic fixtures always, and existing real
recordings unless `--force`. It only works with `--live` (offline, the replies already are fixtures).
