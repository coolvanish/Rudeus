# Rudeus — design direction

Rudeus is an AR game for children in Kazakhstan. A child's drawing or toy becomes the character; the real table
becomes the level (cups are obstacles, books are platforms, the table edge is a cliff). This document is the
source of truth for everything visible in `:shared` (Compose UI) and for the HUD that `:androidApp` draws on top
of the AR view. Code tokens live in `shared/src/commonMain/kotlin/kz/rudy/ar/ui/theme/Tokens.kt`; copy lives in
`shared/src/commonMain/kotlin/kz/rudy/ar/ui/Strings.kt`. If this file and the code disagree, fix the code.

## (a) Principles

1. **The camera feed is the canvas.** The AR view (a slot composable from `:androidApp`) fills the screen on
   Scanning and Play. The UI is a thin overlay: paper pills, chips and one button. Nothing opaque covers the middle
   third of the screen where the character lives.
2. **One primary action per screen.** Capture: *Photo*. Processing: *Next* / *Play anyway* / *Retry*. Scanning: *Scan*.
   Play: *Jump* / *Fly* (ROLL: *Rescan*). Metrics: *Export JSON*. Everything else is secondary, ghost or a chip.
3. **Heights:** primary 64 dp, secondary and chips 48 dp; every hit area ≥ 56 dp (`hitArea`: a real pointer area, because over the camera the AR view underneath wins any near miss).
   Children tap with the whole fingertip; the phone is often held at arm's length over a table.
4. **Icons + 1–3 word labels.** Never an icon alone, never a sentence on a button. Glyphs are drawn with Canvas
   (no icon dependency), labels come from the copy table.
5. **Kazakh first, then Russian; English only for the demo video.** `LocalLang` defaults to `KK`. The language
   switch is in the Settings sheet (opened from Capture only); it never appears during play.
6. **Bright & clean.** Warm white paper, one Sun action, quiet chrome. No cartoon mascots in the chrome, no
   confetti, no rainbow gradients. The child's own drawing is the only character.
7. **Readable in daylight on a table.** We shoot in daylight near a window. Every text sits on paper or on an
   ink scrim; never text directly on the camera feed. Body text is 17 sp SemiBold; nothing a child reads is below
   13 sp / 700.
8. **Honest states.** The gate score is shown as a number; a failed gate still lets the child play with a note.
   Tracking loss says "Paused", never resets the character.
9. **One edge rule.** 2 dp Ink stroke = tappable. Cards, tiles and banners have no stroke; they sit on Cream. HUD
   surfaces over the camera get a 1 dp `Line` edge. No shadows.

## (b) Tokens

### Colour — "bright & clean", light only

The AR view is always a live camera feed, so there is no dark theme: the UI is paper and ink laid over whatever
the camera sees.

| Token | Hex | Role |
|---|---|---|
| `Paper` | `#FFFDF8` | Page, HUD surfaces |
| `Cream` | `#FAF4E8` | Borderless card fill |
| `CreamDeep` | `#F2E9D6` | Pressed, tracks, tags |
| `Line` | `#E8DFCD` | 1 dp HUD edge, list dividers |
| `Ink` | `#1D1A16` | Text, 2 dp interactive stroke |
| `InkSoft` | `#5D564E` | Secondary text (7.2:1 on Paper) |
| `InkMuted` | `#968E84` | Disabled / decorative only |
| `Sun` | `#FFD02B` | The one primary per screen, under Ink text (13.4:1); never white on Sun |
| `SunDeep` | `#F2B90F` | Sun pressed |
| `SunTint` | `#FFF4C7` | Disabled primary fill, icon wells |
| `Sky` / `SkyDeep` / `SkyTint` | `#1E6FE0` / `#1659B8` / `#E7F0FC` | Secondary: ability tags, switch on, footprints on, mic |
| `Leaf` / `LeafTint` | `#2E8540` / `#E4F2E7` | Success: gate passed, scan done, offline-but-working |
| `Tomato` / `TomatoTint` | `#D93A2F` / `#FCE8E6` | Error: gate failed, pipeline error, no plane |
| `Grape` / `GrapeTint` | `#7B3FBF` / `#F1E9FA` | Dynamic things (hand), rare accent |
| `Hazard` | `#FF6D00` | Cliff |
| `Scrim` | `Ink @ 55 %` | Behind the Settings and error sheets |
| `ScrimStrong` | `Ink @ 75 %` | Paused, full screen |

Rules: text on the camera feed always sits on `Paper` (pill, banner, bubble, chip) or on a scrim. Sun is used once per
screen. Tomato is never used for decoration. Status colours always pair with a word or a dot + title.

#### Footprint debug overlay tints (per `ObjectKind`)

Drawn by `:androidApp` on the plane and by the 2D mini-map in the HUD. Fill at 35 % alpha, outline 3 dp solid at
90 % alpha, label in Ink on Paper.

| `ObjectKind` | Token | Hex | Why |
|---|---|---|---|
| `OBSTACLE` | `FootObstacle` | `#D93A2F` (Tomato) | "stop" — the character is pushed out |
| `PLATFORM` | `FootPlatform` | `#2E8540` (Leaf) | "go" — walkable, step-up |
| `DYNAMIC` | `FootDynamic` | `#7B3FBF` (Grape) | moves between scans (hand) |
| `CLIFF` | `FootCliff` | `#FF6D00` (hazard orange), dashed outline, no fill | plane boundary; dashes read as "edge" |

The character's collision circle is Sky at 35 %; the tap target is a Sun ring.

### Typography — Nunito

Nunito (OFL; 600/700/800/900 TTFs in `composeResources/font`, covers Kazakh Cyrillic). Children read short bold words;
we never use Light/Regular weights for anything a child reads.

| Style | Size / line | Weight | Use |
|---|---|---|---|
| `display` | 32 / 38 sp | ExtraBold (800) | Screen headline on paper screens |
| `title` | 24 / 30 sp | ExtraBold (800) | Section and sheet titles, character name |
| `headline` | 19 / 25 sp | ExtraBold (800) | Shelf titles |
| `body` | 17 / 24 sp | SemiBold (600) | Hints, notes |
| `label` | 16 / 20 sp | ExtraBold (800) | Buttons (primary 18 sp), chips, pills |
| `caption` | 13 / 18 sp | Bold (700) | Secondary lines |
| `mono` | 14 / 18 sp | Medium (500), monospace | Metrics numbers, run ids |
| wordmark | 28 / 28 sp | Black (900) | "Rudeus" on Capture |

Material mapping: `displayLarge = display`, `headlineMedium = titleLarge = title`, `titleMedium = headline`,
`bodyLarge = body`, `labelLarge = label`, `bodySmall = caption`, `bodyMedium = mono`.

### Spacing, radii, elevation

- Spacing scale: **4 / 8 / 12 / 16 / 20 (screen edge) / 24 / 32 / 48 dp**. Section gap 24, HUD inset from the safe area 16.
- Radii: tag 8, tile 16, card 20, hero + sheet 28, bubble 24, controls full.
- Stroke 2 dp, interactive only. HUD hairline 1 dp `Line`. Elevation: none.

### Components

- **Primary button:** Sun, 2 dp Ink, 64 dp. Disabled = SunTint fill, InkMuted stroke + text (no alpha over the camera).
- **Secondary button:** Paper, 2 dp Ink, 48 dp. **Ghost:** no stroke. Press: scale 0.96 and Sun → SunDeep / Paper → CreamDeep.
- **Chip:** 48 dp, Paper, 2 dp Ink; on = SkyTint fill, Sky stroke, SkyDeep text.
- **Segmented control** (Table|Floor, language): Paper pill, 2 dp Ink, 4 dp inset; the selected option fills Ink with
  Paper text (+ check glyph for Table/Floor).
- **Switch:** 56×34, 2 dp Ink track; on = Sky track, Paper knob.
- **Tag:** 24 dp, radius 8, 12 sp 800, tint variants.
- **Character card:** Cream, no stroke, 124 dp wide; sprite on a Paper well; name 16 sp 800; ability as a small Sky tag.
  Selected = 2 dp Ink ring + Sun check badge.
- **Pill** (HUD status): Paper, 1 dp Line edge, 40 dp.
- **Banner:** Paper card, 1 dp Line, status dot + title + hint, optional one action (Skip, Retry).
- **Speech bubble:** Paper, 1 dp Line, Sun tail + Sun underline accent.
- **Progress:** 14 dp, 2 dp Ink edge, Sun fill. **Stages:** 4-column stepper (40 dp icon circles: done Leaf tint + check,
  active Sun + Ink ring).
- **Sheet:** Paper, top radius 28, handle, over Scrim.

## (c) Motion

Easings: `Standard = cubic-bezier(0.2, 0, 0, 1)`, `Emphasised = cubic-bezier(0.05, 0.7, 0.1, 1)`,
`Sine = EaseInOutSine`. Springs: `Pop = spring(dampingRatio 0.55, stiffness Medium)`.

| Animation | Spec |
|---|---|
| **Scan "looking around"** (3–5 s, default 4000 ms, driven by the real scan duration) | Four Sun corner brackets with a 2 dp Ink edge breathe (scale 1.0 → 1.04, 900 ms, Sine, reverse). A pair of cartoon eyes in the centre: pupils sweep left → right → left, 1400 ms per cycle, Sine; a blink (eye height 1 → 0.1 → 1, 120 ms) every 2.8 s. A Sun progress arc on a Paper track with an Ink edge (0 → 360°, linear, `durationMs`) around the eyes. Label "Looking…" on a paper pill above the buttons. Ends with the arc snapping to full and the eyes widening (scale 1.15, 150 ms, Emphasised) before the "Table ready!" banner. |
| **Speech bubble pop** | Enter: scale 0.6 → 1.0 with `Pop` spring (≈ 220 ms, visible overshoot to ~1.05) + fade 120 ms; transform origin at the tail. Exit: fade 150 ms + scale to 0.9, Standard. Auto-dismiss after 2500 ms; a new line replaces the old without exiting (text crossfade 150 ms). |
| **Jump squash-stretch** (rendered by `:androidApp` on the billboard quad; the spec is here) | Anticipation: 80 ms, scaleX 1.2 / scaleY 0.8. Rise: 150 ms scaleX 0.85 / scaleY 1.15, Emphasised. Apex: 1.0 / 1.0. Land: 100 ms scaleX 1.25 / scaleY 0.75 then back to 1.0 in 120 ms with `Pop`. Walk cycle: bob scaleY 1.0 ↔ 0.94 at 6 Hz; flip on `facing.u` sign. |
| **Button press** | Scale 1.0 → 0.96 in 90 ms Standard on press, back in 150 ms with `Pop`. Container Sun → SunDeep (secondary and ghost → CreamDeep). |
| **Banner enter/exit** | Slide from the nearest edge 24 dp + fade, 200 ms Emphasised; exit 150 ms Standard. |
| **Pipeline stage change** | Stage row ticks: check glyph scales 0 → 1 with `Pop`; progress bar animates to the new fraction in 300 ms Standard. Gate score counts up 0 → score over 600 ms. |
| **Fall / respawn** | Handled by `:androidApp`: quad drops 0.3 m with gravity, fades 150 ms; respawns at `Level.spawn` with the pop spring. HUD shows the "Oops! Fell" bubble. |

**Reduced motion** (`LocalReducedMotion = true`: the Settings toggle OR the system's animator duration scale at 0, which the host provides above `App`): no infinite
loops (eyes and brackets are static, pupils centred), progress arc still animates (it conveys time), pops become
150 ms fades, buttons do not scale, banners fade only. No parallax, ever.

## (d) Screens and states

Navigation is a single `Screen` enum in `App.kt`; screens are stateless composables fed plain data + callbacks.

**One AR session.** `App` composes the AR slot exactly once, full-screen in a `Box` under every screen, while the
screen is Scanning or Play (and while Metrics is opened from Play: Metrics is drawn on top and takes the touches).
Scanning -> Play keeps the same ARCore session and its single anchor. Leaving to Capture (or opening Metrics from
Capture) ends the session; the next Scanning starts again from "Look at the table". The `ArSlot` carries the sprite,
the surface and the debug flag; the host reports surface ready, anchor placed, tracking and character events back.

### Capture — `CaptureScreen`
Paper. Top-left wordmark "Rudeus ⌄" (the arrow in Sky) opens the Settings sheet. Display title "Drawing or toy", hint "Put the drawing on
the table and take a photo". Hero card (Cream, radius 28): example drawing with Paper viewfinder brackets, **Photo**
(Sun, full width, camera glyph), ghost **From gallery**, and under them the caption "The photo goes to Meta AI…"
(`capture_privacy_note`). Then the headline "Or pick a ready one" and a shelf of character cards bleeding off the right
edge. The card draws the character's own sprite PNG (shipped in the APK), else a host thumbnail, else a glyph. Without a
host the demo pair is shown. Characters made from the child's photos come first, newest first: one joins the shelf when
it passes the check, or when the child taps "Play anyway" (below the check, Retry adds nothing). The 30 newest stay on
the phone across restarts.
- *API offline*: Paper pill with a Leaf dot "No internet — ready characters" above the shelf; the bundled shelf is the
  primary path. Photo / From gallery stay enabled: a tap is the retry (the host only goes back online after a call
  succeeds). A failed attempt lands on the Processing error sheet as today. Never show a spinner.
- *No bundled characters* (should not happen; assets ship in the APK): an empty Cream card "Nothing yet".

### Settings sheet — `SettingsSheet`
Bottom sheet over Capture: Language (ҚАЗ · РУС · ENG segmented; the only language switch), Sounds (off = the host plays
no character sounds; speech bubbles stay), Reduce motion (adds to the system value: it can turn reduced motion on, not
off), Metrics (dev tag, chevron), About (version, mono). The system back gesture, a tap on the
scrim or a drag on the sheet closes it.

### Processing — `ProcessingScreen`
Paper. Back (ghost) top-left, which also cancels the run. Title "Making your character". Big Cream hero with the
photo on a Paper well (the finished sprite replaces it). Progress bar + 4-stage stepper
(Cutting out · Painting · Checking · Naming). When the gate stage completes the score is shown **honestly** on a Cream
card: mono number `0.82`, "Style score", "Attempt 2 / 3", Passed (Leaf) or Not passed (Tomato) tag.
- *Gate failed after retries*: hero with the sprite, name, ability tag, a Tomato dot "Style score 0.61 · Not passed",
  note "Style may look a bit different", primary **Play anyway**, ghost **Retry**. Never block the child.
- *Pipeline error / offline mid-way*: sheet over Scrim "Didn't work" + "Check the internet and try again", primary
  **Retry**, ghost **Pick a ready one** (back to Capture where bundled characters are offered).
- *Done*: "Done!", large sprite on the hero, name + ability tag, a Leaf dot score line, one Sun **Next** (the child looks
  at their character first; no auto-advance).

### Scanning — `ScanningScreen`
AR view fills the screen. Top-centre: segmented **Table | Floor** (selected fills Ink, with a check), visible until the
character is placed; the choice survives Back. Bottom: secondary **Back** + primary **Scan** (eye glyph). Scan is
explicit; never per frame. Floor means floor, carpet or outdoors (grass, pavement): the same game at a bigger scale;
every "table" line below has a floor variant.
- *No surface yet*: paper pills "Look at the table" + "Move the phone slowly" (quiet); Scan disabled.
- *Surface found, not placed*: one pill "Tap the table — it stands there"; Scan disabled. The tap places the
  character (the session's one anchor); it greets the child in a bubble.
- *Placed*: pills and chips gone; Scan enabled.
- *Scanning*: button disabled; `ScanOverlay` plays the looking-around animation for the real scan duration
  (3–5 s); pill "Looking…" above the buttons.
- *No plane found* (after 10 s without a plane, or scan returns nothing): Tomato-dot banner at the top "No table
  found" + "Use a patterned cloth and light from the side" (floor: "No floor found" + "Try outside in daylight:
  grass or pavement"), button becomes **Scan** again. **Skip** appears inside the banner after the second failure
  (2D fallback: unbounded `Level`), and in the bottom row while no banner is up. *Table is empty*: Sky-dot banner with **Play anyway** inside it.
- *Done*: Leaf-dot banner "Table ready!" (floor: "Floor ready!") for 1.5 s, then Play. The object count ("Objects 5")
  is shown only when the scan found objects; until the real scan (T04) lands it finds 0 and shows no count.
- *Tracking paused during scan*: same Paused scrim as Play; the scan is cancelled, not retried automatically.

### Play — `PlayScreen(arView, …)`
AR view; HUD in a `Box` on top, everything inside the safe-drawing insets:
- Top-left: `AbilityChip` (glyph + "Jump"), Paper with Ink stroke. Tapping it says the ability line in a bubble.
- Top-right: offline pill (Leaf dot, "Offline") when `offline`; `Footprints` toggle chip (debug; Sky when on).
  Footprints on (debug only): secondary **Metrics** and **Scenario** (`play_scenario`) under it; Scenario replays the
  Day 5 script from where the character stands (`ArSlot.scenarioRequest`). There is no on-screen Back: the system back
  gesture returns to Capture.
- Centre-bottom: `SpeechBubble` with the character's line from `CharacterSheet.lines[lang]` (falls back
  kk → ru). Event-triggered (`HudSpeech`): placed -> line 1; jumped -> line 2; blocked -> "Blocked!"; fell ->
  "Oops! Fell"; respawned -> "Back up"; every 3rd arrival -> a sheet line (deterministic); the ability chip -> line 2.
  Walking, landing, stepping up, pause/resume say nothing. Bubbles are ≥ 2.5 s apart; Fell and Back up always
  replace the current one, except that "Back up" waits until "Oops! Fell" has been up 1.2 s.
- Bottom-left: secondary **Scan** (eye glyph), which reads **Rescan** once a scan has run in this session. Above the row
  the hint pill "Tap — it walks": appears when the character is placed, leaves 5 s after it first walks (or on Scan).
- Bottom-right: the ability's action as the Sun primary, ≥ 156 dp wide, under the thumb: **Jump** ("Секір") for JUMP and
  HIDE, **Fly** ("Ұш") for FLY. ROLL has no ability button (it rolls fast on its own): Scan / Rescan becomes the primary,
  right-aligned. Each tap is one jump (`ArSlot.jumpRequest`); physics ignores it in the air. Disabled while tracking is
  lost.
- *Size* (T25): two round 56 dp buttons stacked at the left edge, plus over minus — Paper fill, 2 dp Ink stroke and glyph
  (disabled: InkMuted).
  Each tap is one `CharacterSize` step (0.5× to 3×, 1× = 6 cm on the table); the character, its collision circle and
  its walking speed follow, while jump height stays the same. Disabled at the ends and while tracking is lost. Kept
  across Back. Icon-only like the mic; accessibility labels `play_bigger`, `play_smaller`.
- *Voice* (T24, only with a `CaptureHost.voice` and once the character is placed): a round 56 dp mic control under
  the ability chip — Paper fill, 2 dp Sky stroke and mic glyph; Sky-filled with a Paper glyph while held. Hold = record
  (pill "Listening…" beside it, `voice_listening`); release = transcribe (pill "Thinking…", `voice_thinking`), then
  "jump" / "прыгай" / "секір" jumps (`ArSlot.jumpRequest`) and "go" / "иди" / "жүр" walks one short step forward
  (10 cm × surface scale, `ArSlot.walkForwardRequest`). What was heard shows for 2 s in the speech bubble with a mic
  glyph beside it; no command word -> "Didn't catch that" (`voice_unknown`). The first press without the microphone
  permission opens the system dialog. English is the demo language. Disabled while offline (the offline pill already
  says why), while tracking is lost and while the last phrase is still being transcribed. The one icon-only control
  (brief T24: a round hold target, its pills are the label); accessibility label `voice_hold`.
- *Offline* (`CaptureHost.offline`): the offline pill shows and the hint row shows the pill "No internet — ready
  characters" (`capture_offline_note`) instead of "Tap — it walks", so the child sees why. Scan / Rescan stays
  enabled: a tap is the retry, a failure says "Table is empty" or, while offline, "No internet". The character keeps
  walking and jumping on the last level. On Scanning, a failed scan while offline shows the error card as "No
  internet" / "Check the internet and try again"; Skip / Play anyway still work.
- Footprints on: the AR view draws tinted footprints; the HUD adds a 120 dp paper mini-map (bottom-left, above Rescan) of the
  `Level` in plane coordinates with the same tints and the character as a Sky dot.
- *Tracking lost / `Mode.PAUSED`*: the HUD goes; full-screen `ScrimStrong` that takes the touches, title "Paused", hint "Point the phone at the
  table" (floor: "…at the floor"), pause glyph. Physics is frozen by `:core`; nothing resets. The banner leaves by itself when tracking
  resumes; there is no button.
- *Fell off the cliff*: bubble "Oops! Fell" then "Back up" on respawn.
- *Scanning from Play*: the same `ScanOverlay`; the character keeps walking underneath.

### Metrics — `MetricsScreen`
Paper, dev density. Opened from the Settings sheet (or from Play with Footprints on). Shows this phone's run
(`CaptureHost.latestRun()`, core's `MetricsRun`, read when the screen opens). Back (ghost) top-left, title "Metrics",
run id + source/provider/device in mono. 4 stat tiles in one row (only when n > 0): Passed first try `80%`, p50 `29.8s`,
p90 `41.0s`, Cost `3.4¢`. Table of characters: input · score · retries · time · cost, 13.5 sp with mono numbers and Cream
stripes. Scans table: objects · time · accuracy cm. **Export JSON** primary pinned to the bottom (disabled while saving);
after export a Leaf tag "Saved" with "Where: Download/Rudeus/<runId>.json" in mono above it.
- *No run yet*: empty Cream card "Nothing yet" + hint, Export disabled. Without a host (demo shell) a demo run is shown
  and Export is disabled.
- *Export failed*: Tomato-dot banner "Not saved" with Retry inside it.
- *Cancelled characters* (the child left Processing): listed with "—" for score and retries and muted text; the header
  counts finished ones like the summary ("Characters 2 · 1 cancelled").

## (e) Copy table

ids are snake_case and match `StringId` in `Strings.kt` one-to-one. Short, imperative, child-friendly.

| id | kk | ru | en |
|---|---|---|---|
| app_name | Rudeus | Rudeus | Rudeus |
| back | Артқа | Назад | Back |
| retry | Тағы бір рет | Ещё раз | Retry |
| cancel | Болдырмау | Отмена | Cancel |
| next | Әрі қарай | Дальше | Next |
| ok | Жарайды | Ладно | OK |
| skip | Өткізу | Пропустить | Skip |
| offline_badge | Офлайн | Офлайн | Offline |
| lang_kk | ҚАЗ | ҚАЗ | KK |
| lang_ru | РУС | РУС | RU |
| lang_en | ENG | ENG | EN |
| capture_title | Сурет немесе ойыншық | Рисунок или игрушка | Drawing or toy |
| capture_hint | Суретті үстелге қой да, фотоға түсір | Положи рисунок на стол и сфотографируй | Put the drawing on the table and take a photo |
| capture_take_photo | Фото | Фото | Photo |
| capture_privacy_note | Фото Meta AI-ға жіберіледі. Адамдарды түсірме | Фото уходит в Meta AI. Не снимай людей | The photo goes to Meta AI. No people, please |
| capture_pick_gallery | Галереядан | Из галереи | From gallery |
| capture_or_pick | Немесе дайынын таңда | Или выбери готового | Or pick a ready one |
| capture_offline_note | Интернет жоқ — дайын кейіпкерлер | Нет интернета — готовые герои | No internet — ready characters |
| capture_empty | Әзірге жоқ | Пока пусто | Nothing yet |
| metrics_button | Метрика | Метрика | Metrics |
| processing_title | Кейіпкер жасалып жатыр | Делаем героя | Making your character |
| stage_cutout | Қиып аламыз | Вырезаем | Cutting out |
| stage_stylize | Бояймыз | Раскрашиваем | Painting |
| stage_gate | Тексереміз | Проверяем | Checking |
| stage_sheet | Атын ойлаймыз | Придумываем имя | Naming |
| gate_score | Стиль ұпайы | Оценка стиля | Style score |
| gate_passed | Өтті | Прошёл | Passed |
| gate_failed | Өтпеді | Не прошёл | Not passed |
| gate_attempt | Әрекет | Попытка | Attempt |
| processing_failed_title | Болмады | Не вышло | Didn't work |
| processing_failed_hint | Интернетті тексер де, қайталап көр | Проверь интернет и попробуй ещё | Check the internet and try again |
| processing_pick_ready | Дайынын таңда | Выбрать готового | Pick a ready one |
| ship_anyway | Бәрібір ойнаймыз | Всё равно играем | Play anyway |
| ship_anyway_note | Стиль сәл басқаша болуы мүмкін | Стиль может чуть отличаться | Style may look a bit different |
| processing_done | Дайын! | Готово! | Done! |
| scan_title | Үстелді қара | Посмотри на стол | Look at the table |
| scan_title_floor | Еденді қара | Посмотри на пол | Look at the floor |
| scan_hint | Телефонды баяу қозғалт | Медленно води телефоном | Move the phone slowly |
| scan_button | Скан | Скан | Scan |
| scanning | Қарап жатырмын… | Смотрю… | Looking… |
| scan_no_plane_title | Үстел табылмады | Стол не найден | No table found |
| scan_no_plane_hint | Өрнекті мата жай, жарық бүйірден түссін | Постели ткань с узором, свет сбоку | Use a patterned cloth and light from the side |
| scan_no_plane_title_floor | Еден табылмады | Пол не найден | No floor found |
| scan_no_plane_hint_floor | Күндіз далада көр: шөп не тротуар | Попробуй днём на улице: трава или тротуар | Try outside in daylight: grass or pavement |
| scan_tap_to_place | Үстелді түрт — ол сонда тұрады | Нажми на стол — он встанет там | Tap the table — it stands there |
| scan_tap_to_place_floor | Еденді түрт — ол сонда тұрады | Нажми на пол — он встанет там | Tap the floor — it stands there |
| scan_done | Үстел дайын! | Стол готов! | Table ready! |
| scan_done_floor | Еден дайын! | Пол готов! | Floor ready! |
| surface_table | Үстел | Стол | Table |
| surface_floor | Еден | Пол | Floor |
| scan_objects_found | Заттар | Предметы | Objects |
| scan_offline_title | Интернет жоқ | Нет интернета | No internet |
| play_tap_to_walk | Түрт — ол барады | Нажми — он пойдёт | Tap — it walks |
| play_jump | Секір | Прыгай | Jump |
| play_fly | Ұш | Лети | Fly |
| play_scenario | Сценарий | Сценарий | Scenario |
| voice_listening | Тыңдап тұрмын… | Слушаю… | Listening… |
| voice_thinking | Ойланып тұрмын… | Думаю… | Thinking… |
| voice_unknown | Түсінбедім | Не понял | Didn't catch that |
| voice_hold | Ұстап тұрып айт | Держи и говори | Hold and talk |
| ar_wait_table | Сәл күт — үстелді іздеп жатырмын | Подожди — ищу стол | Wait — finding the table |
| ar_wait_floor | Сәл күт — еденді іздеп жатырмын | Подожди — ищу пол | Wait — finding the floor |
| rescan | Қайта скан | Скан снова | Rescan |
| ability_jump | Секіру | Прыжок | Jump |
| ability_fly | Ұшу | Полёт | Fly |
| ability_roll | Домалау | Качение | Roll |
| ability_hide | Жасырыну | Прятки | Hide |
| paused_title | Үзіліс | Пауза | Paused |
| paused_hint | Телефонды үстелге бағытта | Наведи телефон на стол | Point the phone at the table |
| paused_hint_floor | Телефонды еденге бағытта | Наведи телефон на пол | Point the phone at the floor |
| footprints | Іздер | Следы | Footprints |
| fell | Ой! Құлап кетті | Ой! Упал | Oops! Fell |
| respawn | Қайта тұрды | Снова на месте | Back up |
| blocked | Кедергі! | Преграда! | Blocked! |
| kind_obstacle | Кедергі | Преграда | Obstacle |
| kind_platform | Алаң | Площадка | Platform |
| kind_dynamic | Қол | Рука | Hand |
| kind_cliff | Жар | Обрыв | Cliff |
| metrics_title | Метрика | Метрика | Metrics |
| metrics_empty | Әзірге жоқ | Пока пусто | Nothing yet |
| metrics_empty_hint | Алдымен кейіпкер жаса | Сначала сделай героя | Make a character first |
| metrics_export | JSON экспорт | Экспорт JSON | Export JSON |
| metrics_exported | Сақталды | Сохранено | Saved |
| metrics_export_failed | Сақталмады | Не сохранилось | Not saved |
| metrics_export_where | Қайда | Где | Where |
| metrics_run | Іске қосу | Запуск | Run |
| metrics_characters | Кейіпкерлер | Герои | Characters |
| metrics_cancelled | тоқтатылды | отменено | cancelled |
| metrics_pass_rate | Бірден өтті | Прошли сразу | Passed first try |
| metrics_p50 | p50 уақыт | p50 время | p50 time |
| metrics_p90 | p90 уақыт | p90 время | p90 time |
| metrics_cost | Бағасы | Цена | Cost each |
| metrics_scans | Скандар | Сканы | Scans |
| metrics_col_input | Сурет | Рисунок | Input |
| metrics_col_score | Ұпай | Оценка | Score |
| metrics_col_retries | Қайталау | Повторы | Retries |
| metrics_col_time | Уақыт | Время | Time |
| metrics_col_cost | Баға | Цена | Cost |
| metrics_col_objects | Заттар | Предметы | Objects |
| metrics_col_within_cm | Дәлдік, см | Точность, см | Accuracy, cm |
| settings_title | Баптаулар | Настройки | Settings |
| settings_language | Тіл | Язык | Language |
| settings_sounds | Дыбыс | Звук | Sounds |
| settings_reduce_motion | Қимылды азайту | Меньше анимации | Reduce motion |
| settings_reduce_motion_hint | Секіру орнына баяу көріну | Плавно вместо прыжков | Fades instead of pops |
| settings_metrics_hint | Іске қосулар, ұпай, экспорт | Запуски, оценки, экспорт | Runs, scores, export |
| settings_about | Бағдарлама туралы | О приложении | About |
| camera_permission_title | Камера керек | Нужна камера | Camera needed |
| camera_permission_hint | Кейіпкерді үстелде көру үшін | Чтобы увидеть героя на столе | To see the character on the table |
| camera_permission_button | Рұқсат беру | Разрешить | Allow |

Character lines (5 per language) come from the `CharacterSheet`, not from this table. Demo fallback lines live in
`App.kt` only.

## (f) Accessibility and what not to do

Accessibility
- Every tappable thing is ≥ 56 dp and has a `contentDescription`/text; glyphs are decorative (`null`) when a
  label sits next to them.
- Colour is never the only signal: Passed/Not passed has a word; footprint kinds have labels on the mini-map;
  the cliff is dashed as well as orange.
- Text ≥ 13 sp bold, body 17 sp; all text on Paper or Scrim; measured contrast ≥ 4.5:1 (Metrics, a dev screen, goes to 11 sp).
- Reduced motion honoured via `LocalReducedMotion` (see Motion). No flashing faster than 3 Hz.
- Languages: kk and ru copy are complete; a missing `CharacterSheet` line falls back kk → ru → en.
- Speech bubbles stay 2.5 s minimum and never cover the character (anchored above the bottom controls).
- Works one-handed: primary button at the bottom (bottom-right on Play), chips top corners, nothing in the middle.

What not to do
- No text directly on the camera feed. No white text on Sun. No shadows for contrast.
- No spinner without words; no progress bar that lies (stage rows reflect real pipeline events).
- No per-frame scanning or auto-rescan; Scan is a button the child presses.
- No reset on tracking loss; Paused is a scrim, not a dialog.
- No modal dialogs for children at all; errors are banners with one action.
- No emoji in the chrome (font-dependent rendering); glyphs are Canvas-drawn.
- No dark theme, no gradients over the feed, no parallax, no confetti.
- No sentences on buttons; no exclamation marks except "Done!", "Table ready!", "Floor ready!", "Oops! Fell",
  "Blocked!" and the character's own lines.
- No branded characters in demo assets; bundled characters are our own drawings.
- `:shared` never imports ARCore/SceneView/Filament; it draws HUD only and receives the AR view as a slot.
