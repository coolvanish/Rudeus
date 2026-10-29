# Rudeus — design direction

Rudeus is an AR game for children in Kazakhstan. A child's drawing or toy becomes the character; the real table
becomes the level (cups are obstacles, books are platforms, the table edge is a cliff). This document is the
source of truth for everything visible in `:shared` (Compose UI) and for the HUD that `:androidApp` draws on top
of the AR view. Code tokens live in `shared/src/commonMain/kotlin/kz/rudy/ar/ui/theme/Tokens.kt`; copy lives in
`shared/src/commonMain/kotlin/kz/rudy/ar/ui/Strings.kt`. If this file and the code disagree, fix the code.

## (a) Principles

1. **The camera feed is the canvas.** The AR view (a slot composable from `:androidApp`) fills the screen on
   Scanning and Play. The UI is a thin overlay: scrims, chips and one button. Nothing opaque covers the middle
   third of the screen where the character lives.
2. **One primary action per screen.** Capture: *Photo*. Processing: *Retry* (only on failure). Scanning: *Scan*.
   Play: *Scan* (re-scan). Metrics: *Export JSON*. Everything else is secondary (ghost style) or a chip.
3. **Tap targets ≥ 56 dp.** Primary buttons are 64 dp tall. Chips are 48 dp tall but sit inside a 56 dp hit area.
   Children tap with the whole fingertip; the phone is often held at arm's length over a table.
4. **Icons + 1–3 word labels.** Never an icon alone, never a sentence on a button. Glyphs are drawn with Canvas
   (no icon dependency), labels come from the copy table.
5. **Kazakh first, then Russian; English only for the demo video.** `LocalLang` defaults to `KK`. The language
   switch is on the Capture screen only; it never appears during play.
6. **Playful, not babyish.** Bold rounded shapes and crayon colours, but no cartoon mascots in the chrome, no
   confetti, no rainbow gradients. The child's own drawing is the only character; the chrome stays quiet.
7. **Readable in daylight on a table.** We shoot in daylight near a window. Every text sits on paper or on an
   ink scrim; never text directly on the camera feed. Minimum body size 20 sp, bold.
8. **Honest states.** The gate score is shown as a number; a failed gate still lets the child play with a note.
   Tracking loss says "Paused", never resets the character.

## (b) Tokens

### Colour — "crayon on paper", light only

The AR view is always a live camera feed, so there is no dark theme: the UI is paper and ink laid over whatever
the camera sees. Contrast ratios below are WCAG 2.x luminance ratios of the solid colours.

| Token | Hex | Role | Contrast notes |
|---|---|---|---|
| `Paper` | `#FFF6E5` | Surfaces: cards, bubbles, chips, the Capture/Processing/Metrics backgrounds | Ink on Paper 15.3:1 |
| `PaperDeep` | `#F3E4C8` | Secondary surface: table rows, progress tracks, chip pressed state | Ink on PaperDeep 12.6:1 |
| `Ink` | `#1F1A17` | Text, outlines (3 dp "crayon" stroke), scrims | On Sun 10.4:1, on Paper 15.3:1 |
| `InkSoft` | `#5C534C` | Secondary text on Paper | 6.4:1 on Paper |
| `Sun` | `#FFB300` | **Primary action** (the one button), progress fill, scan brackets | Ink text on Sun 10.4:1; do not put white on Sun |
| `SunDeep` | `#E09A00` | Sun pressed state | |
| `Sky` | `#1E6FE0` | Secondary/info: ability chip accent, links, "Looking…" | Paper text on Sky 5.0:1 |
| `Leaf` | `#2E7D32` | Success: gate passed, scan done, offline-but-working | Paper on Leaf 5.5:1 |
| `Tomato` | `#D93A2F` | Error: gate failed, pipeline error, no plane | Paper on Tomato 5.2:1 |
| `Grape` | `#7B3FBF` | Dynamic things (hand), rare accent | Paper on Grape 6.4:1 |
| `Scrim` | `Ink @ 55 %` | Behind any text over the camera feed; full-screen on Paused | Paper text on Scrim over a bright table ≥ 4.5:1 in daylight test |
| `ScrimStrong` | `Ink @ 75 %` | Pause / tracking-lost banner, processing error sheet | |

Rules: text on the camera feed always sits on `Scrim` (banner) or `Paper` (bubble, chip). Sun is used once per
screen. Tomato is never used for decoration.

#### Footprint debug overlay tints (per `ObjectKind`)

Drawn by `:androidApp` on the plane and by the 2D mini-map in the HUD. Fill at 35 % alpha, outline 3 dp solid at
90 % alpha, label in Ink on Paper.

| `ObjectKind` | Token | Hex | Why |
|---|---|---|---|
| `OBSTACLE` | `FootObstacle` | `#D93A2F` (Tomato) | "stop" — the character is pushed out |
| `PLATFORM` | `FootPlatform` | `#2E7D32` (Leaf) | "go" — walkable, step-up |
| `DYNAMIC` | `FootDynamic` | `#7B3FBF` (Grape) | moves between scans (hand) |
| `CLIFF` | `FootCliff` | `#FF6D00` (hazard orange), dashed outline, no fill | plane boundary; dashes read as "edge" |

The character's collision circle is Sky at 35 %; the tap target is a Sun ring.

### Typography — system sans, bold

Children read short bold words; we never use Light/Regular weights for anything a child reads.

| Style | Size / line | Weight | Use |
|---|---|---|---|
| `display` | 40 / 46 sp | Bold (700) | Screen headline on paper screens ("Дайын!"), gate score number |
| `title` | 28 / 34 sp | Bold (700) | Section titles, banner titles, speech bubble text |
| `body` | 20 / 26 sp | SemiBold (600) | Hints, table rows, notes |
| `label` | 16 / 20 sp | Bold (700), +0.5 sp tracking | Buttons, chips, badges, table headers |
| `mono` | 16 / 20 sp | Medium (500), monospace | Metrics numbers, run ids |

Material mapping: `displayLarge = display`, `headlineMedium = title`, `titleLarge = title`, `bodyLarge = body`,
`labelLarge = label`, `bodyMedium = mono`.

### Spacing, radii, elevation

- Spacing scale: **4 / 8 / 16 / 24 / 32 dp** (`RudeusSpacing.xs .. xl`). Screen edge padding 16, section gap 24,
  HUD inset from safe area 16.
- Radii: **16 dp** (cards, banners, mini-map), **24 dp** (speech bubble, sheets), **full** (buttons, chips, badges).
- Elevation: none. Depth comes from the 3 dp Ink outline ("crayon stroke") and from scrims, not shadows.
  Shadows disappear over a bright camera feed; outlines do not.
- Scrim rules: a scrim is only as big as the text it protects plus 16 dp padding, except Paused (full screen,
  `ScrimStrong`) and Processing error (bottom sheet, `ScrimStrong`).

## (c) Motion

Easings: `Standard = cubic-bezier(0.2, 0, 0, 1)`, `Emphasised = cubic-bezier(0.05, 0.7, 0.1, 1)`,
`Sine = EaseInOutSine`. Springs: `Pop = spring(dampingRatio 0.55, stiffness Medium)`.

| Animation | Spec |
|---|---|
| **Scan "looking around"** (3–5 s, default 4000 ms, driven by the real scan duration) | Four Sun corner brackets breathe (scale 1.0 → 1.04, 900 ms, Sine, reverse). A pair of cartoon eyes in the centre: pupils sweep left → right → left, 1400 ms per cycle, Sine; a blink (eye height 1 → 0.1 → 1, 120 ms) every 2.8 s. A Sun progress arc (0 → 360°, linear, `durationMs`) around the eyes. Label "Looking…" on a scrim pill. Ends with the arc snapping to full and the eyes widening (scale 1.15, 150 ms, Emphasised) before the "Table ready!" banner. |
| **Speech bubble pop** | Enter: scale 0.6 → 1.0 with `Pop` spring (≈ 220 ms, visible overshoot to ~1.05) + fade 120 ms; transform origin at the tail. Exit: fade 150 ms + scale to 0.9, Standard. Auto-dismiss after 2500 ms; a new line replaces the old without exiting (text crossfade 150 ms). |
| **Jump squash-stretch** (rendered by `:androidApp` on the billboard quad; the spec is here) | Anticipation: 80 ms, scaleX 1.2 / scaleY 0.8. Rise: 150 ms scaleX 0.85 / scaleY 1.15, Emphasised. Apex: 1.0 / 1.0. Land: 100 ms scaleX 1.25 / scaleY 0.75 then back to 1.0 in 120 ms with `Pop`. Walk cycle: bob scaleY 1.0 ↔ 0.94 at 6 Hz; flip on `facing.u` sign. |
| **Button press** | Scale 1.0 → 0.96 in 90 ms Standard on press, back in 150 ms with `Pop`. Container Sun → SunDeep. |
| **Banner enter/exit** | Slide from the nearest edge 24 dp + fade, 200 ms Emphasised; exit 150 ms Standard. |
| **Pipeline stage change** | Stage row ticks: check glyph scales 0 → 1 with `Pop`; progress bar animates to the new fraction in 300 ms Standard. Gate score counts up 0 → score over 600 ms. |
| **Fall / respawn** | Handled by `:androidApp`: quad drops 0.3 m with gravity, fades 150 ms; respawns at `Level.spawn` with the pop spring. HUD shows the "Oops! Fell" bubble. |

**Reduced motion** (`LocalReducedMotion = true`, set by `:androidApp` from the system animator scale): no infinite
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
Paper background (or a camera preview slot). Headline "Drawing or toy", hint "Put the drawing on the table and
take a photo", one Sun button **Photo** (camera glyph). Below: "Or pick a ready one" and a horizontal row of
bundled character cards (sprite + name, 96 dp, Ink outline; selected = Sun outline). The card draws the
character's own sprite PNG (shipped in the APK), else a host thumbnail, else a glyph. Without a host the demo pair
is shown. Top-right: `KK / RU / EN`
segmented switch (the only place it exists) and a ghost **Metrics** button bottom-left.
- *API offline*: Leaf badge "No internet — ready characters" above the row; the Photo button is disabled (50 %
  alpha, still 64 dp) and the bundled row is the primary path. Never show a spinner.
- *No bundled characters* (should not happen; assets ship in the APK): row shows an empty paper card "Nothing yet".

### Processing — `ProcessingScreen`
Paper. Title "Making your character". Four stage rows with glyph + label (Cutting out · Painting · Checking ·
Naming); the active row has a Sun dot pulsing, done rows a Leaf check. One Sun progress bar. When the gate
stage completes the score is shown **honestly** as a display number `0.82` with "Style score" and "Passed"
(Leaf) or "Not passed" (Tomato) and "Attempt 2 / 3". Cutout thumbnail (if provided) appears at 160 dp with the
crayon outline so the child sees progress.
- *Gate failed after retries*: Tomato "Not passed · 0.61", note "Style may look a bit different", primary
  **Play anyway** (Sun), ghost **Retry**. Ship anyway is the default; never block the child.
- *Pipeline error / offline mid-way*: `ScrimStrong` bottom sheet "Didn't work" + "Check the internet and try
  again", primary **Retry**, ghost **Back** (which returns to Capture where bundled characters are offered).
- *Done*: "Done!" display, the finished sprite at 160 dp with the crayon outline, character name, ability chip, and
  one Sun **Next** (the child looks at their character first; no auto-advance). *Not passed* shows the sprite too.

### Scanning — `ScanningScreen`
AR view fills the screen. Top-centre: two chips **Table | Floor** (the selected one Sky-outlined with a check),
visible until the character is placed; the choice survives Back. Bottom-centre: one Sun button **Scan** (eye glyph),
ghost **Back** to its left. Scan is explicit; never per frame. Floor means floor, carpet or outdoors (grass,
pavement): the same game at a bigger scale; every "table" line below has a floor variant.
- *No surface yet*: scrim pills "Look at the table" + "Move the phone slowly"; Scan disabled.
- *Surface found, not placed*: one pill "Tap the table — it stands there"; Scan disabled. The tap places the
  character (the session's one anchor); it greets the child in a bubble.
- *Placed*: pills and chips gone; Scan enabled.
- *Scanning*: button disabled; `ScanOverlay` plays the looking-around animation for the real scan duration
  (3–5 s); label "Looking…".
- *No plane found* (after 10 s without a plane, or scan returns nothing): Tomato-accented banner "No table
  found" + "Use a patterned cloth and light from the side" (floor: "No floor found" + "Try outside in daylight:
  grass or pavement"), button becomes **Scan** again. A ghost **Skip** appears after the second failure (2D
  fallback: unbounded `Level`).
- *Done*: Leaf banner "Table ready!" (floor: "Floor ready!") for 1.5 s, then Play. The object count ("Objects 5")
  is shown only when the scan found objects; until the real scan (T04) lands it finds 0 and shows no count.
- *Tracking paused during scan*: same Paused scrim as Play; the scan is cancelled, not retried automatically.

### Play — `PlayScreen(arView, …)`
AR view; HUD in a `Box` on top, everything inside the safe-drawing insets:
- Top-left: `AbilityChip` (glyph + "Jump"), Paper with Ink outline. Tapping it says the ability line in a bubble.
- Top-right: offline badge (Leaf, "Offline") when `offline`; `Footprints` toggle chip (debug; Sky when on);
  ghost **Metrics**.
- Centre-bottom: `SpeechBubble` with the character's line from `CharacterSheet.lines[lang]` (falls back
  kk → ru). Event-triggered (`HudSpeech`): placed -> line 1; jumped -> line 2; blocked -> "Blocked!"; fell ->
  "Oops! Fell"; respawned -> "Back up"; every 3rd arrival -> a sheet line (deterministic); the ability chip -> line 2.
  Walking, landing, stepping up, pause/resume say nothing. Bubbles are ≥ 2.5 s apart; Fell and Back up always
  replace the current one, except that "Back up" waits until "Oops! Fell" has been up 1.2 s.
- Bottom: one Sun button **Scan** (eye glyph), which reads **Rescan** once a scan has run in this session. Above it
  the hint pill "Tap — it walks": appears when the character is placed, leaves 5 s after it first walks (or on Scan).
- Footprints on: the AR view draws tinted footprints; the HUD adds a 120 dp paper mini-map (bottom-left) of the
  `Level` in plane coordinates with the same tints and the character as a Sky dot.
- *Tracking lost / `Mode.PAUSED`*: full-screen `ScrimStrong`, title "Paused", hint "Point the phone at the
  table" (floor: "…at the floor"), pause glyph. Physics is frozen by `:core`; nothing resets. The banner leaves by itself when tracking
  resumes; there is no button.
- *Fell off the cliff*: bubble "Oops! Fell" then "Back up" on respawn.
- *Scanning from Play*: the same `ScanOverlay`; the character keeps walking underneath.

### Metrics — `MetricsScreen`
Paper. Shows this phone's run (`CaptureHost.latestRun()`, core's `MetricsRun`, read when the screen opens).
Title "Metrics", run id + source/provider/device in mono. Summary tiles (2 × 2, only when n > 0): Passed first try
`80 %`, p50 `29.8 s`, p90 `41.0 s`, Cost `$0.034`. Table of characters: input · score · retries · time · cost (rows
in `PaperDeep` stripes). Scans table: objects · ms · accuracy cm. One Sun button **Export JSON** (disabled while
saving); after export a Leaf toast "Saved" with "Where: Download/Rudeus/<runId>.json" under it in mono. Ghost
**Back** (to where Metrics was opened from).
- *No run yet*: display "Nothing yet", hint, Export disabled. Without a host (demo shell) a demo run is shown and
  Export is disabled.
- *Export failed*: Tomato banner "Not saved" with Retry.
- *Cancelled characters* (the child left Processing): listed with "—" for score and retries, a neutral InkSoft
  accent and muted text; the header counts finished ones like the summary ("Characters (2 · 1 cancelled)").

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
| play_tap_to_walk | Түрт — ол барады | Нажми — он пойдёт | Tap — it walks |
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
- Text ≥ 16 sp bold, body 20 sp; all text on Paper or Scrim; measured contrast ≥ 4.5:1.
- Reduced motion honoured via `LocalReducedMotion` (see Motion). No flashing faster than 3 Hz.
- Languages: kk and ru copy are complete; a missing `CharacterSheet` line falls back kk → ru → en.
- Speech bubbles stay 2.5 s minimum and never cover the character (anchored above the bottom controls).
- Works one-handed: primary button bottom-centre, chips top corners, nothing in the middle.

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
