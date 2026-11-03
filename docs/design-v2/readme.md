# Rudeus design system

Rudeus is an AR game for children in Kazakhstan: a child's drawing becomes the character, the table becomes the level. The UI is a thin, bright layer over a live camera feed — warm white paper, Ink text, one Sun-yellow action per screen, Sky for secondary things. Nunito everywhere. No shadows: edges come from a 2px Ink stroke on things you can tap, and from cream tints on things you can't.

## How to use

- Link `styles.css` from every page and take every value from its variables (`--color-*`, `--space-*`, `--radius-*`, `--size-*`, `--font-*`).
- Build with the classes below; component pages are plain HTML — view source and copy.
- `Rudeus Screens.html` shows every app screen built from these classes.
- `handoff/` holds `Tokens.kt` and `DESIGN-update.md` for the Compose codebase.

## Rules

1. **One Sun per screen.** The primary action is a 64px Sun pill with Ink text. Never white on Sun.
2. **2px Ink = tappable.** Buttons, chips, segmented controls, switches carry the stroke. Cards, tiles and banners never do.
3. **Cards are borderless on cream** (`--color-cream` on `--color-paper`). Selection adds the Ink ring + a Sun check.
4. **Never text on the camera feed.** It sits on a paper pill, banner or bubble (1px `--color-line` edge) or on a scrim.
5. **Icon + 1–3 word label.** The only icon-only control is the round hold-to-talk mic.
6. **Status colours always pair with a word or dot + title.** Leaf ok, Tomato error, Grape dynamic, Hazard cliff.
7. **Heights:** primary 64, secondary 48, every hit area ≥ 56. Screen edge 20, section gap 24.

## Type

Nunito 600–900; JetBrains Mono 500 for numbers and ids. Display 32/38 800 · Title 24/30 800 · Headline 19/25 800 · Body 17/24 600 · Label 16/20 800 · Caption 13/18 700. Wordmark: Nunito 900. Covers Kazakh Cyrillic (Ә Ғ Қ Ң Ө Ұ Ү Һ І).

## Components

| Class | What |
| --- | --- |
| `.btn` `.btn-primary` `.btn-ghost` `.btn-action` `.btn-round` `.btn-block` | Buttons. Default `.btn` is the 48px ink-outlined secondary. `.btn-action` = Sky ability action. |
| `.chip` (`.is-on`) | HUD toggles (ability, footprints) |
| `.seg` + `.seg-opt` | Segmented choice — selected option fills Ink |
| `.switch` | On/off — on fills Sky |
| `.card` `.card-lg` `.card-title` `.card-body` | Borderless cream cards |
| `.char-card` `.char-art` `.char-name` `.char-check` | Character picker card |
| `.tag` `.tag-sky/-leaf/-tomato/-grape/-sun` | Small labels |
| `.pill` `.pill-quiet` `.dot-*` | HUD status pills |
| `.banner` `.banner-title` `.banner-hint` | Status card: dot + title + hint (+ one action) |
| `.bubble` | Character speech bubble, Sun tail |
| `.progress` `.stages` `.stage` | Pipeline progress + compact stepper |
| `.sheet` `.sheet-handle` `.scrim` `.scrim-strong` | Bottom sheet; no modal dialogs |
| `.list` `.list-row` `.list-icon` | Settings rows |
| `.stat` `.table` `.num` | Metrics tiles and compact data table |
| `.eye` | Scan overlay eyes |
| `.glyph` + `<use href="#g-*">` | Line glyphs (`assets/glyphs.svg.html` sprite) |

## Motion

Standard `cubic-bezier(.2,0,0,1)`, Emphasised `cubic-bezier(.05,.7,.1,1)`, Pop spring (damping 0.55). Button press 0.96 / 90 ms. Reduced motion: no loops, fades instead of pops.

## Don't

No shadows, gradients over the feed, dark theme, emoji, confetti or mascots in the chrome. No modal dialogs. No spinners without words.
