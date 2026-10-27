# Rudeus — AR game: your drawing is the character, your table is the level

Android-only ARCore game (Kotlin Multiplatform). A child's drawing or toy becomes the character;
real table objects become obstacles, platforms and cliffs. Hackathon deadline: **Wed Nov 4 2026, 9:00 PM ET**.
The day-by-day plan, gates and cut lines live in `ai/build-plan.md` — read it before planning any work.

## Modules and boundaries (enforced by hooks + review)

| Module | Target | May depend on | Must NOT contain |
|---|---|---|---|
| `:core` | KMP android + jvm | kotlinx, Ktor | Compose, Android UI, ARCore/Filament/SceneView types |
| `:shared` | KMP android (Compose UI) | `:core` | ARCore/SceneView types, network calls |
| `:androidApp` | Android app (AR host) | `:shared`, `:core`, SceneView | business logic, physics, API parsing |
| `:cli` | JVM main | `:core` | anything Android |

- The AR view is injected into `App()` as a **slot composable** from `:androidApp`. No `expect/actual`, no stub actuals.
- `:core` works in **plane coordinates** `(u, v, h)` in metres. Only `:androidApp` converts to/from ARCore `Pose`.
- Scan is an **explicit button** (3–5 s), never per-frame. One anchor per session. `PAUSED` freezes physics, never resets.
- No backend. Ktor runs on the phone; the key reaches the app via `local.properties` → `BuildConfig.META_API_KEY`.

## Hand-review zone (agents propose, a human approves)
`androidApp/**` AR/Filament code, every `*.gradle.kts`, `gradle/libs.versions.toml`, `settings.gradle.kts`.
Agents work freely in `:core`, `:cli`, `:shared` and tests. When you must touch the zone, keep the diff minimal,
add a `// HAND-REVIEW:` comment explaining the intent, and say so in your final message.

## Secrets
- Never read, print, or edit `local.properties` or dotenv files. Never echo `META_API_KEY`. Never commit a key.
- Saved API responses in `docs/api-samples/` must be redacted (a hook and the pre-commit scan check for token shapes).

## Testing policy — important parts only
Test: physics (push-out, step-up, cliff, jump), geometry (ray–plane, Douglas–Peucker, footprint), pipeline
(gate threshold 0.7, ≤2 retries, fixture round-trip), metrics JSON schema, `FixtureApi`.
Do not test: Compose screens, AR rendering, HUD layout, the CLI argument parser.
Tests live in `core/src/commonTest` and run on the JVM: `./gradlew :core:jvmTest`.

## Definition of done for any change
1. `scripts/verify.sh` passes (core tests + shared host test + Android debug build). The Stop hook will nag if you skip it.
2. No new dependency without a one-line reason in the task note.
3. The relevant day gate in `ai/build-plan.md` is still reachable, or you name what to cut.

## Commands
```
scripts/verify.sh                 # all tests + assembleDebug (the DoD)
./gradlew :core:jvmTest           # fast domain tests
./gradlew :androidApp:installDebug && scripts/device.sh run   # on the Pixel
./gradlew :cli:run --args="character --fixtures docs/api-samples --out runs"
scripts/new-task.sh T07 short-slug # create an ai/tasks brief
```

## Agents (in `.claude/agents/`) — pick the cheapest tier that can do the job
| Agent | Model / effort | Use for |
|---|---|---|
| `rudeus-haiku` | haiku / low | lookups, reading test XML, running a given command, device checks. Read-only. |
| `rudeus-sonnet-high` | sonnet / high | Compose screens from DESIGN.md, copy kk/ru/en, fixtures, briefs, docs. |
| `rudeus-opus-high` | opus / high | the default for a task brief: pipeline, parsers, metrics, UI wiring, clear bug fixes. |
| `rudeus-opus-extra` | opus / xhigh | geometry, physics edge cases, numeric tuning, root-causing a failing core test. |
| `rudeus-fable-high` | fable / high | cross-module design, AR host drafts for hand-review, gate go/no-go, adversarial review. |
| `rudeus-reviewer` | opus / high, read-only | independent review of a finished brief before merge; gets the diff, not the implementer's story. |
| `rudeus-device-qa` | sonnet / medium, adb | install, drive screens, screenshots to docs/evidence/, logcat, pull runs from the Pixel. |
Rule: never dispatch a tier above what the task needs; one brief = one agent; parallel agents own disjoint paths.
Flow for a brief: implement (opus-high / opus-extra / sonnet-high) -> rudeus-reviewer -> rudeus-device-qa when it touches the app -> human on the Pixel for AR.

## Skills (in `.claude/skills/`)
`verify`, `ar-sceneview`, `meta-fixtures`, `task-brief`, `metrics`, `gate`. Load the one that matches before working.
Library docs: use Context7 (`/sceneview/sceneview`, `/websites/developers_google_ar`, Ktor, kotlinx) — never guess an API.

## Conventions
- Kotlin official style. Distances in metres as `Float`. Angles in radians. Times in ms as `Long`.
- Serializable data classes for anything that crosses a module boundary or lands in JSON.
- Language strings: `kk` (Kazakh) first, `ru`, then `en`. Keep copy short — the player is a child.
- Commit messages: `area: what changed` (`core: circle-vs-polygon push-out`). Tag `v1.0` at code freeze (Day 7).
