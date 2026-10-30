# T24 — voice-commands

status: todo
created: 2026-10-31
day: Day 6
gate: Day 6 — fresh install -> character -> full scenario with Wi-Fi off halfway, no crash (voice must fail silently offline)
cut-if-slips: this IS cut line 1 — cut the whole brief first if any Day 5/6 gate is at risk; ship nothing half-wired

## Goal
Push-to-talk: the child holds the mic button on Play, says "jump" (or "прыгай" / "секір") or "go" ("иди" / "жүр"),
and the character jumps or walks a step forward. English is the demo language (T01 decision, Oct 28: Kazakh is not
an ASR language; the kk/ru keywords are accepted when the model happens to return them). Recording is raw 16 kHz
mono PCM16 from `AudioRecord`, sent through the existing `MetaModels.transcribe` (so the `OfflineGuard` sees it and
the offline badge stays honest). Nothing else is voice-controlled.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/voice/VoiceCommands.kt (new) + core/src/commonTest/kotlin/kz/rudy/ar/core/voice/VoiceCommandsTest.kt (new)
- shared/src/commonMain/kotlin/kz/rudy/ar/VoiceHost.kt (new), CaptureHost.kt (one member), ArSlot.kt (one field), App.kt, ui/screens/PlayScreen.kt, ui/Strings.kt (new ids), DESIGN.md Play section
- androidApp/src/main/kotlin/kz/rudy/ar/voice/VoiceRecorder.kt (new), di/AndroidCaptureHost.kt (implement VoiceHost; minimal), MainActivity.kt (RECORD_AUDIO launcher; minimal), ar/RudeusArView.kt (ONE LaunchedEffect for `walkForwardRequest`)
- this brief

Runs AFTER T21 is merged (both touch `RudeusArView.kt`). Hand-review zone files get `// HAND-REVIEW: T24` comments.

## Contract
### :core
```kotlin
enum class VoiceCommand { JUMP, WALK }
object VoiceCommands {
    /** Keyword match on a normalised transcript (lowercase, punctuation stripped): the FIRST keyword that appears
     *  decides. JUMP: jump, jumped, hop, прыгай, прыг, прыжок, секір; WALK: go, walk, run, forward, иди, вперёд, жүр, бар.
     *  Null when nothing matches. Pure. */
    fun parse(transcript: String): VoiceCommand?
}
```
### :shared
```kotlin
interface VoiceHost {
    fun hasPermission(): Boolean
    fun requestPermission()                           // system dialog; the result arrives through hasPermission() later
    fun startListening(): Boolean                     // true = recording; false = no permission / mic busy
    suspend fun stopAndTranscribe(lang: String): String?  // stops, transcribes (<= 5 s of audio); null on any failure. Never throws.
}
interface CaptureHost { ... val voice: VoiceHost? get() = null }
data class ArSlot(... val walkForwardRequest: Int = 0 /* each increment = walk 10 cm x profile.scale along `facing` */)
```
Play HUD: a round mic `GhostButton`-styled control under the ability chip, only when `capture?.voice != null` and
the character is placed. Hold = `startListening()` (button turns Sky-filled, pill "Listening…"); release =
`stopAndTranscribe(lang.code)` (pill "Thinking…"), then `VoiceCommands.parse` -> JUMP: `jumpRequest++`,
WALK: `walkForwardRequest++`; the transcript is shown for 2 s as a speech bubble prefixed with a mic glyph
(`HudSpeech.say`). No permission -> tap calls `requestPermission()`. Offline -> the button is disabled with the
offline pill (reuse T20's). Strings: `voice_listening` ("Тыңдап тұрмын…"/"Слушаю…"/"Listening…"),
`voice_thinking` ("Ойланып тұрмын…"/"Думаю…"/"Thinking…"), `voice_unknown` ("Түсінбедім"/"Не понял"/"Didn't catch that").

### :androidApp
- `VoiceRecorder`: `AudioRecord(MIC, 16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)`, reads on a background thread into a
  growing buffer capped at 5 s (160 000 bytes); `stop()` returns the PCM bytes. Releases the recorder; never throws
  out (log + null).
- `AndroidCaptureHost : VoiceHost`: `stopAndTranscribe` = `guard.transcribe("voice-<n>", pcm, lang)` on IO with a
  10 s `withTimeoutOrNull`; logs `voice_ms=<ms> text_len=<n>` (never the key).
- `MainActivity`: a `RequestPermission` launcher for RECORD_AUDIO handed to the host (`requestPermission()` launches it).
  The manifest already declares RECORD_AUDIO.
- `RudeusArView`: `LaunchedEffect(slot.walkForwardRequest) { if (> 0) loop.walkTo(state.pos + state.facing * (0.10f * profile.scale)) }`,
  clamped to the level boundary (skip + log `voice_walk_outside_boundary` when outside).

## Tests required
- `VoiceCommandsTest`: `englishJumpAndGo`, `russianAndKazakhKeywords`, `firstKeywordWins` ("go and then jump" -> WALK),
  `punctuationAndCaseIgnored`, `unknownIsNull`.
Tests not required: audio, the HUD, the host.

## Steps
1. Read T01's voice row, `MetaApi.transcribe`, `WavHeader.kt`, `OfflineGuard.kt`, T20's Play HUD, `RudeusArView.kt` after T21.
2. Core + test; shared; host. Build: `:core:jvmTest`, `:shared:compileAndroidMain`, `:androidApp:assembleDebug`.
3. Fill the Result; status `review`. No git.

## Done when
- `scripts/verify.sh` exit 0 (orchestrator); the five core tests present.
- Result lists the Pixel checks: hold-say-release "jump" -> JUMPED within ~3 s; "go" -> one step; Wi-Fi off -> button disabled, no crash.

## Result (filled on acceptance)
- ...
