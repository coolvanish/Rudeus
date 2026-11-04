# T31 — shared-cast-emote-hud

status: review
created: 2026-11-04
day: Day 9 (Nov 4, v1.0 minimal cut; see ai/plan-cast-emotes.md). Starts after T28 and T29 are merged.
gate: v1.0 gate "characters are added from the grid on Play; the emote row plays on the active character and the bubble names the speaker"
cut-if-slips: the Add sheet (one character, as Day 7) before the emote row; the emote row is never cut

## Goal
On Play the child sees who is on the table (a row of round chips with the sprites, the active one ringed), adds
another character from the saved / bundled grid with a `+` chip (up to four), and makes the active one say hi,
say bye, dance, spin or roar with a row of five buttons. The speech bubble shows the character's line and, with two
or more on the table, its name first. The Day 7 AR host keeps working unchanged until T32 lands: the old slot fields
keep mirroring the active character.

## Owns (exact paths)
- shared/src/commonMain/kotlin/kz/rudy/ar/ArSlot.kt, App.kt, CaptureHost.kt
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/Strings.kt, ui/components/Glyph.kt, ui/components/** (new CastChips.kt, EmoteRow.kt allowed), ui/screens/PlayScreen.kt, ui/screens/HudSpeech.kt, ui/screens/CaptureScreen.kt (only to expose the grid for the Add sheet)
- shared/src/commonTest/kotlin/kz/rudy/ar/** 
- DESIGN.md (Play section: Cast chips, Emote row, Add sheet; the copy table rows)
Must NOT touch: `core/`, `androidApp/`, `cli/`, Gradle files.

## Contract
`ArSlot` — additive, nothing removed (the Day 7 host compiles and runs against it):
```kotlin
/** One character the host puts on the table. [id] is the saved id, the bundled ref, or "photo-<ref>" for a fresh pipeline result. */
data class ArCharacter(val id: String, val name: String, val spritePng: ByteArray?, val ability: Ability, val emotes: EmoteSet)

sealed interface CharacterAction {
    data object Jump : CharacterAction
    data object WalkForward : CharacterAction
    data class Emote(val emote: kz.rudy.ar.core.emote.Emote) : CharacterAction
}
/** Edge-triggered on [serial] (strictly rising within a session; the host applies each new serial once). */
data class CharacterCommand(val serial: Int, val id: String, val action: CharacterAction)

// New ArSlot fields (defaults keep every existing call site compiling):
val characters: List<ArCharacter> = emptyList()        // the cast in order; first = the one Play started with
val activeId: String? = null                            // whose taps and buttons; the first member until a chip or a finger picks another
val onActiveChanged: (String) -> Unit = {}              // host -> UI: a finger landed on this character
val command: CharacterCommand? = null                   // null when nothing is pending; resets to null when the AR session ends
val onCastEvent: (String, CharacterEvent) -> Unit = { _, _ -> }   // per member; a T32 host calls this and NEVER onCharacterEvent
```
- Legacy mirror: `spritePng` and `ability` are the active character's; `jumpRequest` / `walkForwardRequest` keep incrementing alongside the matching `command` (a Day 7 host obeys them; a T32 host ignores them). `onCharacterEvent` keeps working for a Day 7 host: App routes both callbacks into the same bubble logic, so a host that calls one or the other produces one bubble.
- `BundledCharacter` gains `emotes: EmoteSet = EmoteSet.defaults()` (CaptureHost.kt); the Android host fills it in T32.
- App state: `cast: List<ArCharacter>`, `activeId`, `commandSerial`. Play starts with one member (the picked bundled / saved character, or the photo result). `+` adds `ArCharacter` from a `BundledCharacter` or a saved one (same type). The session end (`arOn` false) resets the cast to its first member and `command` to null; Back to Capture clears everything as today.
- Play HUD (DESIGN.md house style: Paper, Ink 2 dp strokes, Sun for the active ring, touch targets ≥ `RudeusSpacing.touchTarget`):
  - Cast chips, top-left where the ability chip sits today (the ability chip moves into the active chip's name pill, e.g. "Мұрат · Секір"): one round chip per member with its sprite (`SpriteImage`, bundled dino glyph when `spritePng` is null), the active ringed in Sun with the name pill; tap = `activeId`. After them the `+` chip (Glyph.PLUS); disabled at `Cast.MAX` with the "Table full" pill on tap.
  - Add sheet: `BottomSheet` titled `play_add_title`, the grid of saved then bundled characters (reuse `CaptureGrid`'s cards; members already on the table are hidden), tap adds and closes. Opening it never ends the AR session (it is a sheet over Play).
  - Emote row: five round `SecondaryButton`-styled buttons in a horizontal strip above the Scan / Jump row, centred, one-word label under each (`emote_hello` …); glyphs drawn in `Glyph.kt` in the house style: HAND (open palm), BOW (bent figure), NOTE (music note), SPIN (circular arrow), ROAR (open mouth / sound waves). Tap -> `command = CharacterCommand(++serial, activeId, Emote(e))` and the bubble says `activeSheet.lines[lang][choreography.line]` at once (`HudSpeech.say`), name-prefixed when the cast has more than one member ("Мұрат: Сәлем!"). Disabled while tracking is lost / paused and before the anchor is placed, like Jump.
  - Jump button -> `command Jump` for the active (plus `jumpRequest++`); voice WALK -> `command WalkForward` (plus `walkForwardRequest++`); voice JUMP -> `command Jump`.
  - Bubbles for `onCastEvent(id, e)`: `HudSpeech.next` with that member's lines (`CharacterSheet.lines[lang]`), name prefix when more than one member. Keep T24's voice bubble behaviour.
- Strings (kk first, ru, en; short, for a child): `play_add("Қос", "Добавить", "Add")`, `play_add_title("Кімді қосамыз?", "Кого добавить?", "Who joins?")`, `play_table_full("Үстел толы", "Стол полон", "Table full")`, `emote_hello("Сәлем", "Привет", "Hi")`, `emote_bye("Сау бол", "Пока", "Bye")`, `emote_dance("Биле", "Танцуй", "Dance")`, `emote_spin("Айнал", "Крутись", "Spin")`, `emote_roar("Ақыр", "Рычи", "Roar")`.
- Reduced motion (`LocalReducedMotion`): the chips and the row use fades, as the other buttons do.

## Tests required (pure, in shared commonTest)
- `HudSpeechTest`: `castLineIsPrefixedWithTheNameOnlyWhenTwoOrMore` (whatever pure helper formats the bubble text), `emoteLineComesFromTheChoreographyIndex`.
- `CastUiTest` (new): `addableCharactersHideThoseAlreadyOnTheTable`, `commandSerialRisesPerAction` on the pure helpers behind the chips / row (extract them so they are testable without Compose).
Tests not required: Compose screens, HUD layout, the AR host.

## Steps
1. Read `ArSlot.kt`, `App.kt` (the slot wiring, the session-end reset, HudSpeech use, `onPickBundled`), `PlayScreen.kt`, `HudSpeech.kt`, `CaptureScreen.kt` (`CaptureGrid`), `BottomSheet.kt`, `Glyph.kt`, `Strings.kt`, DESIGN.md (Play, Motion, copy table), T28's `Cast.MAX` and T29's `Emote` / `EmoteSet`.
2. Contract first (ArSlot, CaptureHost, App state), then the components, then PlayScreen, then DESIGN.md.
3. `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug --console=plain -q` while iterating (the Day 7 host must keep compiling); `scripts/verify.sh` at the end.

## Done when
- `scripts/verify.sh` exit 0; the Day 7 host still builds and, on the emulator (`Pixel_7_Pro` AVD, the host-less shell is fine), Play shows the chips, the `+` sheet adds a second character to the list, and the emote row says a line in the bubble.
- Commit: `shared: cast chips, Add sheet, emote row; ArSlot cast + command contract (T31)`.

## Result (filled on acceptance)
- measured: `scripts/verify.sh` exit 0 (core 305, shared 20 incl. HudSpeechTest 7 / CastUiTest 2, android 2; debug APK built).
  The Day 7 host compiles unchanged against the additive ArSlot. No new dependency.
- built: ArSlot `characters` / `activeId` / `onActiveChanged` / `command` / `onCastEvent` + `ArCharacter`,
  `CharacterAction`, `CharacterCommand`; `BundledCharacter.emotes` (it lives in CaptureScreen.kt, as before). Pure
  helpers in `ui/components/CastUi.kt` (member, canAdd, addable, add, active, next) and `HudSpeech.speaker` /
  `spoken` / `emoteLine` (+ optional `speaker` on `next` / `say`). Components `CastChips.kt`, `EmoteRow.kt`,
  `CharacterCardGrid` (CaptureScreen.kt) for the Add sheet; glyphs HAND, BOW, NOTE, SPIN, ROAR.
- notes: `ArCharacter` compares by identity (it holds a ByteArray, like ArSlot). `commandSerial` is never lowered,
  so serials rise across sessions too (`command` itself resets to null). A chip with no sprite shows its ability
  glyph (there is no dino glyph). The host-less shell enables the emote row without an anchor (nothing can anchor
  there). Back closes the Add sheet first. A photo member is `photo-<ref>`; its saved card (`saved-…`) is a different
  id, so it still shows in the Add sheet.
- review follow-up: a photo member hides its own saved card in the Add sheet (`CastUi.addable(alsoOnTable)`, App
  remembers the kept card id); `+` moved to the start of the name-pill line (top line <= 248 dp); tracking lost closes
  the Add sheet; emote / name-pill lines wait while "Back up" is queued (`HudSpeech.holdsFall`); `onCastEvent` ignores
  ids not on the table ("" stays the Day 7 path); ArSlot KDoc: a member added before the anchor is placed on the
  anchor's frame. Tests: HudSpeechTest 8, CastUiTest 3.
- Pixel checks: the bottom stack grew ~94 dp (emote row), so the bubble / mini-map may reach the size buttons; the
  emote labels may truncate at font scale > 1.0 ("Сау бол", "Крутись"); the chips and `+` before the anchor exists.
- cut/deferred: none. Still needed: the emulator check from "Done when" (device QA) and the commit (left to a human:
  this agent runs no git).
