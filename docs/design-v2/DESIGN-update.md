# DESIGN.md update — v2 "bright & clean"

Drop-in replacements for the sections of `Rudeus/DESIGN.md` that change. Sections not listed (motion specs, accessibility, copy table except additions) stay. Screens: `Rudeus Screens.html`. Tokens: `handoff/Tokens.kt`.

## (a) Principles — changes
- 3. **Heights:** primary 64 dp, secondary 48 dp; every hit area ≥ 56 dp.
- 6. **Playful, not babyish → bright & clean.** Warm white paper, one Sun action, quiet chrome. The child's drawing is the only character.
- 7. Body text 17 sp SemiBold (was 20 sp Bold). Nothing a child reads is below 13 sp / 700.
- New 9. **One edge rule.** 2 dp Ink stroke = tappable. Cards, tiles, banners have no stroke; they sit on Cream. HUD surfaces over the camera get a 1 dp `Line` edge. No shadows.

## (b) Tokens — colour

| Token | Hex | Role |
|---|---|---|
| Paper | #FFFDF8 | Page, HUD surfaces |
| Cream | #FAF4E8 | Borderless card fill |
| CreamDeep | #F2E9D6 | Pressed, tracks, table stripes |
| Line | #E8DFCD | 1 dp HUD edge, list dividers |
| Ink | #1D1A16 | Text, 2 dp interactive stroke |
| InkSoft | #5D564E | Secondary text (7.2:1) |
| InkMuted | #968E84 | Disabled / decorative only |
| Sun | #FFD02B | The one primary per screen. Ink text (13.4:1) |
| SunDeep | #F2B90F | Sun pressed |
| SunTint | #FFF4C7 | Disabled primary fill, icon wells |
| Sky | #1E6FE0 | Secondary: ability tags, switch on, footprints on, mic |
| Leaf / Tomato / Grape / Hazard | #2E8540 / #D93A2F / #7B3FBF / #FF6D00 | ok / error / dynamic / cliff — each with a `-Tint` |

## (b) Tokens — type
Nunito (OFL, bundle 600/700/800/900 TTFs). Display 32/38 800 · Title 24/30 800 · Headline 19/25 800 · Body 17/24 600 · Label 16/20 800 · Caption 13/18 700 · Mono 14/18 500. Wordmark Nunito 900 28 sp.

## (b) Spacing, radii, elevation
- Spacing 4 / 8 / 12 / 16 / 20 (screen edge) / 24 / 32 / 48.
- Radii: tag 8, tile 16, card 20, hero + sheet 28, bubble 24, controls full.
- Stroke 2 dp (was 3 dp), interactive only. Elevation: none.

## Component changes
- **Primary button:** Sun, 2 dp Ink, 64 dp. Disabled = SunTint fill, InkMuted stroke + text (no alpha over the camera).
- **Secondary button:** Paper, 2 dp Ink, 48 dp. Ghost: no stroke. Ability action: Sky stroke + text.
- **Segmented control** (Table|Floor, language): Paper pill, 2 dp Ink, 4 dp inset; selected option fills Ink with Paper text (+ check glyph for Table/Floor).
- **Switch** (new): 56×34, 2 dp Ink track; on = Sky track, Paper knob.
- **Character card:** Cream, no stroke, 124 dp wide; sprite on a Paper well; name 16 sp 800; ability as a small Sky tag. Selected = 2 dp Ink ring + Sun check badge.
- **Pill** (HUD status): Paper, 1 dp Line edge, 40 dp. Replaces Ink-scrim pills.
- **Banner:** Paper card, 1 dp Line, status dot + title + hint, optional one action (Skip, Retry).
- **Speech bubble:** Paper, borderless (1 dp Line), Sun tail + Sun underline accent.
- **Progress:** 14 dp, 2 dp Ink edge, Sun fill. **Stages:** 4-column compact stepper (40 dp icon circles: done Leaf tint + check, active Sun + Ink ring).
- **Sheet** (new, Settings): Paper, top radius 28, handle, over Scrim.

## (d) Screen changes
- **Capture:** top-left wordmark "Rudeus ⌄" opens the Settings sheet. Display title + hint. Hero card (Cream, r28): example drawing with Paper viewfinder brackets, **Photo** (Sun, full width), ghost **From gallery**. Then "Or pick a ready one" shelf, bleeding off the right edge. Language switch and Metrics button **move into Settings**.
- **Settings sheet (new):** Language (ҚАЗ · РУС · ENG segmented), Sounds, Reduce motion (overrides the system value), Metrics (dev tag →), About (version, mono).
- **Processing:** Back (ghost) top-left. Title. Big Cream hero with the cutout on Paper and the original photo as a small inset. Progress + 4-stage stepper. Gate score card: mono number, "Style score", attempt, Passed/Not passed tag.
- **Done:** "Done!", large sprite, name + ability tag, score line, **Next**.
- **Scanning:** Table|Floor segmented top-centre; Paper pills below; Back (secondary) + Scan (primary) bottom. Looking: Sun brackets and progress ring with a 2 dp Ink edge so they read on a white table; eyes in the centre. No plane: Tomato-dot banner at the top with **Skip** inside it.
- **Play:** top-left ability chip + round Sky mic under it; top-right Footprints chip. Bottom: **Rescan** secondary bottom-left, **Jump/Fly** as the Sun primary bottom-right (thumb), ≥ 156 dp wide. Scan is no longer the primary on Play. ROLL characters: Rescan becomes the primary, right-aligned.
- **Metrics:** dev density — 4 stat tiles in one row, 13.5 sp table with mono numbers and Cream stripes, Export JSON primary pinned to the bottom.

## (e) Copy — new ids

| id | kk | ru | en |
|---|---|---|---|
| settings_title | Баптаулар | Настройки | Settings |
| settings_language | Тіл | Язык | Language |
| settings_sounds | Дыбыс | Звук | Sounds |
| settings_reduce_motion | Қимылды азайту | Меньше анимации | Reduce motion |
| settings_reduce_motion_hint | Секіру орнына баяу көріну | Плавно вместо прыжков | Fades instead of pops |
| settings_metrics_hint | Іске қосулар, ұпай, экспорт | Запуски, оценки, экспорт | Runs, scores, export |
| settings_about | Бағдарлама туралы | О приложении | About |

Removed from Capture: `metrics_button` (now a Settings row, label `metrics_title`).
