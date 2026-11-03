# T27 — robe-icon-and-intro

status: review
created: 2026-11-03
day: Day 7 polish, asked for by the user
gate: none; the Day 7 gate stays reachable, nothing in the game loop changed
cut-if-slips: one line: `var introDone by remember { mutableStateOf(true) }` in App.kt hides the intro; the icon stays

## Goal
A launcher icon that is not the Android template: a grey hooded robe modelled on the one Rudeus wears in the anime, drawn
from scratch. On launch, a short motion-design intro that explains the game without reading: draw a hero, it comes
alive, the table is the level, tap to walk.

## Owns
`androidApp/src/main/res/{drawable,drawable-v24,mipmap-*,values,values-v31}`, the manifest's theme line,
`shared/.../ui/screens/IntroScreen.kt`, `shared/.../ui/components/RobeMark.kt`, `docs/brand/`.

## Result
- Icon: adaptive foreground + Sun background + monochrome layer; legacy PNGs rendered from the same shapes;
  `docs/brand/rudeus-icon.svg` is the source, `rudeus-icon-512.png` is for a store listing.
- System splash (Android 12+) shows the icon on Sun, then IntroScreen opens with the same badge in the same place.
- IntroScreen: 12 s (user's call, was 8 s; `INTRO_MS` sets it), one clock, every frame a pure function of it. Over Capture in App.kt, not a `Screen`, so it fades
  into a screen that is already there. Tap anywhere, Skip or Back ends it. Reduced motion: five stills with fades.
- No new dependency. No test: the policy excludes Compose screens.
- Plays on every cold start. A "first launch only" would need one persisted flag from the host.

## Checks
- Emulator (Pixel_7_Pro): docs/evidence/t27-*-emulator-2026-11-03.png. Icon in the app drawer, all four beats, the
  fade to Capture, reduced motion (`reduced_motion_host=true`), no crash in logcat.
- UNTESTED on the Pixel: real frame rate, system-splash handover on Android 16, how the Kazakh captions read to a child.

## Review (rudeus-reviewer, 2026-11-03): MERGE, 7 minors
- FIXED: reduced motion started each pose from blank Paper (white flash on launch, Skip and the dots blinked). It now
  crossfades two pictures and the first still does not fade in.
- FIXED: the crayon tip and the pop ticks ignored the hero's scale pivot; both now use `heroToStage`. The crayon also
  fades in now instead of popping.
- FIXED: `introDone` is `rememberSaveable`, so the intro does not replay over Processing after process death.
- Open: the Settings toggle cannot reach the intro; it opens only with the system's animator scale at 0.
- Open: per-frame Path allocations (measure on the Pixel before changing). TalkBack can reach Capture under the overlay.
- Not recorded: ending by Back, Skip and tap. docs/evidence/t27-intro-reduced-motion-* predates these fixes.
- The user ran the intro on the Pixel before the fixes and liked it; they will install and check the fixed build themselves.
- rudeus-device-qa flows with `input tap` must allow for the intro: the first tap only dismisses it.
