# T26 — ui-theme-v2

status: review
created: 2026-11-03
day: post Day 7 polish (deadline Wed Nov 4, 9:00 PM ET)
gate: app builds, all six screens render in the v2 theme on the Pixel, no gameplay regressions
cut-if-slips: do Phase A + B first (theme + Capture/Settings + Play). Phase C screens are cuttable one by one. Never ship a half-migrated screen — each screen is either fully v2 or untouched.

## Goal
The app looks like a finished product instead of a prototype. Same flows, same copy ids, same `:core` contracts — new visual layer: warm white Paper, borderless Cream cards, one Sun primary per screen, 2 dp Ink stroke only on tappables, Nunito type, paper HUD over the camera. The language switch and Metrics move into a new Settings sheet opened from the "Rudeus ⌄" wordmark on Capture. On Play, Jump becomes the Sun primary (bottom-right) and Rescan the secondary (bottom-left).

## Reference (read first, in this order)
1. `docs/design-v2/DESIGN-update.md` — the spec delta. Merge it into `DESIGN.md` **before** coding (DESIGN.md is source of truth; code follows it).
2. `docs/design-v2/Rudeus Screens.html` — open in a browser. 12 phone mockups; every dimension, colour and layout comes from here. `docs/design-v2/styles.css` holds the exact values (CSS px = dp, CSS px font = sp).
3. `docs/design-v2/components/*.html`, `docs/design-v2/foundations/*.html` — per-component specimens. `foundations/icons.html` has the glyph paths (24 grid, 2.2 stroke, round caps) to port into `Glyph.kt` Canvas drawing.
4. `docs/design-v2/Tokens.kt` — the new token file, ready to drop in.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- DESIGN.md
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/theme/** (Tokens.kt, RudeusTheme.kt)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/components/**
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/screens/**
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/Strings.kt (additions only)
- shared/src/commonMain/kotlin/kz/rudy/ar/App.kt (Settings sheet state + reduced-motion override only)
- shared/src/commonMain/composeResources/font/** (new)

## Contract
- Do NOT change: `Screen` enum flow, `ArSlot`, `CaptureHost`, `VoiceHost`, `HudSpeech` logic, any `:core` or `:androidApp` code, any `*.gradle.kts`. If something seems to need a Gradle change, stop and say so (hand-review zone).
- All existing `StringId`s stay; add only the 7 `settings_*` ids from DESIGN-update.md (kk, ru, en complete).
- `metrics_button` on Capture is removed from the UI (the id may stay in Strings.kt).
- Reduced motion: effective value = Settings toggle OR system animator scale (the host's existing `LocalReducedMotion` value). Toggle state lives in `App` memory; persistence not required.
- Sounds toggle: if a sound player is reachable from `:shared`, gate it; otherwise the toggle is UI-only with a `// TODO(T26): wire sounds` comment.
- Play: JUMP/HIDE → Sun primary "Jump", FLY → Sun primary "Fly", ROLL → no ability button and Rescan becomes the Sun primary, right-aligned. Rescan otherwise is the 48 dp secondary bottom-left.
- Hit targets ≥ 56 dp everywhere; contentDescription rules from DESIGN.md (f) unchanged.
- No shadows (`elevation = 0`, no `shadow()` modifiers), no gradients, no emoji.

## Tests required
Tests not required: Compose screens, HUD layout, theme values (per CLAUDE.md testing policy). Existing tests must stay green.

## Steps
### Phase A — theme
1. Fonts: download Nunito from https://fonts.google.com/specimen/Nunito ("Get font" → download zip). From `static/` take Nunito-SemiBold, -Bold, -ExtraBold, -Black and save as `shared/src/commonMain/composeResources/font/nunito_semibold.ttf`, `nunito_bold.ttf`, `nunito_extrabold.ttf`, `nunito_black.ttf` (lowercase, underscores — Compose resources require it). OFL licence: add a line to `LICENSES/README.md`. If the agent cannot download, stop and ask the human to drop the four files in.
2. Replace `ui/theme/Tokens.kt` with `docs/design-v2/Tokens.kt`. Fix the `Res` import package to match the one already used for `compose-multiplatform.xml` (run `./gradlew :shared:generateComposeResClass` if accessors are missing).
3. `RudeusTheme.kt`: use `rudeusTypography()`; colorScheme from the new tokens (primary = Sun, onPrimary = Ink, background/surface = Paper, surfaceVariant = Cream, outline = Ink, error = Tomato).
4. Rename call sites: `PaperDeep` → `Cream` (card fills) or `CreamDeep` (pressed / stripes / tracks). `RudeusSpacing.stroke` is now 2 dp — check nothing hard-codes 3.dp.
### Phase B — components + the two most-seen screens
5. `Buttons.kt`: Primary (64 dp, Sun, 2 dp Ink, label 18 sp 800, disabled = SunTint fill + InkMuted stroke/text, no alpha), Secondary (48 dp, Paper, 2 dp Ink), Ghost (48 dp, no stroke), Action (Sky stroke + text), Round (56 dp; held = Sky fill, Paper glyph). Press: scale 0.96 / 90 ms Standard, back with `pop()`.
6. `Chips.kt`: Chip (48 dp, `isOn` = SkyTint fill, Sky stroke, SkyDeep text); SegmentedControl (Paper pill, 2 dp Ink, 4 dp inset, selected option fills Ink with Paper text). New `Switch` (56×34, see components/forms.html). New `Tag` (24 dp, r8, 12 sp 800, tint variants). New `Pill` (Paper, 1 dp Line, 40 dp). New `CharacterCard` (see components/cards.html). New `BottomSheet` (Paper, top r28, handle, over Scrim).
7. `Glyph.kt`: add globe, sound, motion, info, cut, paint, shield, name, export, close, down, right, gallery if missing — port from foundations/icons.html.
8. `CaptureScreen.kt` + new `SettingsSheet.kt` — match mockups 01, 02, 03. Check the kk layout (02): the title wraps to two lines and the hero well shrinks; nothing clips.
9. `PlayScreen.kt` — mockup 11 (and 12 Paused). `SpeechBubble.kt`: Paper, 1 dp Line, Sun tail + 4 dp Sun underline (components/hud.html).
### Phase C — remaining screens (cut individually if time runs out)
10. `StatusBanner.kt` → Paper card, 1 dp Line, status dot + title + hint + optional one action. `ScanningScreen.kt` — mockups 08, 10 (Skip inside the banner). `ScanOverlay.kt` — mockup 09: brackets and ring with a 2 dp Ink edge under the Sun stroke.
11. `ProcessingScreen.kt` — mockups 05, 06 (progress bar with Ink edge + 4-column stage stepper; Done with large sprite + Next).
12. `PermissionScreen.kt` — mockup 04. `MetricsScreen.kt` — mockup 07 (4 stat tiles in one row, 13.5 sp table, mono numbers, Export pinned bottom).
13. Delete now-unused composables/tokens; no dead code.

## Done when
- `scripts/verify.sh` exit 0
- rudeus-device-qa: screenshots of every changed screen in en + kk saved to `docs/evidence/ui-2026-11-0X-v2-<screen>-<lang>.png`, compared side by side with `docs/design-v2/Rudeus Screens.html`; list any deviation > 4 dp or any wrong colour.
- Human on the Pixel: one full flow Capture → pick ready character → Scanning → Play → Jump → Rescan → Settings → Metrics → Export, no crash, text readable in daylight over a white table.

## Result (filled on acceptance)
- measured: scripts/verify.sh green (184 core, 12 shared, 2 android tests). Screens in docs/evidence/ui-2026-11-03-v2-*.png, emulator Pixel_7_Pro (411 dp wide, mockups are 360). Capture and Settings come from the real app; Scanning, Play, Processing, Metrics and Permission states were rendered through a throwaway gallery over docs/design-v2/assets/camera-table.png (no ARCore on the emulator; gallery deleted, not in the tree).
- run id: none, no pipeline run in this brief.
- UNTESTED: the human flow on the Pixel from "Done when" (AR, real camera, daylight legibility, photo pipeline).
- deviations from the mockups: Permission keeps Allow mid-screen and has no own Back, because the host draws it in the AR slot under the Scanning HUD, whose bottom row would cover a bottom button and already has Back. Processing's running hero shows the photo, not the cutout: PipelineEvent carries no cutout bytes. Skip stays behind two failures (existing flow; mockup 10 shows it at once) and sits in the bottom row when no banner is up. Scans table has no Surface column: ScanMetrics has none. About shows "Rudeus 1.0" without a build date.
- beyond the brief's owned paths: composeResources/drawable/capture_example.jpg (hero image), App.kt passes `photo` to ProcessingScreen and drops Play's `onBack`.
- cut/deferred: Sounds toggle is UI-only (TODO(T26) in App.kt; the player is in :androidApp). The host does not feed the system animator scale into LocalReducedMotion, so only the Settings toggle reduces motion today.
