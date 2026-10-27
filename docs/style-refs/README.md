# docs/style-refs — the 8 culled style references (provisional)

The reference set Muse Image edit receives with every cutout (`character --refs docs/style-refs`) and Spark scores
the gate against. **Provisional**: generated overnight on 2026-10-28 so the Day 2 gate could be measured at all;
replace them with a hand-picked set whenever you like (same commands below).

## How they were made
1. 12 candidates, one per subject, with `refs generate` (`POST /v1/images/generations`, `muse-image-1.0`,
   `--size 1024x1024`, output 1600x1600 PNG, $0.01 each, 11–24 s each). The prompt is one fixed style sentence
   with the subject swapped in — see `prompts.txt`. Style follows DESIGN.md "crayon on paper": dark ink outline,
   flat crayon colours, white background.
2. Downscaled to 640 px (`sips -Z 640`) to keep the repo small.
3. `refs cull --from <candidates> --keep 8 --out docs/style-refs` (RefCuller, T09). `cull.json` lists what was
   kept and why the rest went: apple, fish, robot and cat were palette outliers (one dominant saturated colour).

Kept: camel, yurt, snow leopard, sheep, star, horse, dinosaur, eagle.

## Replace them
```
cli/build/install/cli/bin/cli refs generate --prompt @my-prompt.txt --n 4 --size 1024x1024 --out /tmp/cands --budget-usd 0.05
cli/build/install/cli/bin/cli refs cull --from /tmp/cands --keep 8 --out docs/style-refs --force
```
(`./gradlew :cli:installDist` first; run from the repo root with JDK 21.) Hand-drawn refs work the same way: drop
scans in a folder and cull. Never put real refs in `docs/api-samples/muse-stylize/refs/` — tests depend on the two
synthetic squares there.
