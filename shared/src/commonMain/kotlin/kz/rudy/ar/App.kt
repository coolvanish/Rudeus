package kz.rudy.ar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.metrics.CharacterMetrics
import kz.rudy.ar.core.metrics.MetricsLog
import kz.rudy.ar.core.metrics.MetricsRun
import kz.rudy.ar.core.metrics.ScanMetrics
import kz.rudy.ar.core.pipeline.CharacterResult
import kz.rudy.ar.core.pipeline.PipelineEvent
import kz.rudy.ar.core.voice.VoiceCommand
import kz.rudy.ar.core.voice.VoiceCommands
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.LocalLang
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.Strings
import kz.rudy.ar.ui.screens.BundledCharacter
import kz.rudy.ar.ui.screens.CaptureScreen
import kz.rudy.ar.ui.screens.ExportStatus
import kz.rudy.ar.ui.screens.HudSpeech
import kz.rudy.ar.ui.screens.HudState
import kz.rudy.ar.ui.screens.MetricsScreen
import kz.rudy.ar.ui.screens.PipelineStage
import kz.rudy.ar.ui.screens.PlayScreen
import kz.rudy.ar.ui.screens.ProcessingScreen
import kz.rudy.ar.ui.screens.ProcessingState
import kz.rudy.ar.ui.screens.ProcessingStatus
import kz.rudy.ar.ui.screens.ScanState
import kz.rudy.ar.ui.screens.ScanStatus
import kz.rudy.ar.ui.screens.ScanningScreen
import kz.rudy.ar.ui.screens.VoicePhase
import kz.rudy.ar.ui.screens.reduce
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusTheme
import kotlin.time.TimeSource

enum class Screen { CAPTURE, PROCESSING, SCANNING, PLAY, METRICS }

/** How long the "Tap — it walks" pill stays after the first walk (DESIGN.md "Play"). */
private const val TAP_HINT_AFTER_WALK_MS = 5000L

/** The scan arc fills to here over SCAN_DEFAULT_MS and then holds: the real duration is the host's, not a timer's. */
private const val SCAN_ARC_HOLD = 0.95f
private const val SCAN_ARC_TICK_MS = 100L

/** T24: a voice transcript bubble stays this long (shorter than a character line: it only confirms what was heard). */
private const val VOICE_BUBBLE_MS = 2000L

/**
 * Minimal navigation shell. :androidApp calls
 * `App(arView = { modifier, slot -> RudeusArView(modifier, slot, scanner = host) }, capture = host)`.
 *
 * One AR session: [arView] is composed exactly once, at one call site in a `Box` under every screen, for as long as
 * the screen is Scanning or Play (and while Metrics is opened from Play, drawn on top of it), so the ARCore session
 * and its single anchor survive Scanning -> Play. Going to Capture (or Metrics from Capture) disposes it; the next
 * Scanning starts a new session. Screens get a no-op `arView`.
 *
 * [arView]'s second argument is the [ArSlot]: the sprite Play shows (captured or bundled, null = the host's default),
 * the chosen [Surface], the debug flag (footprints on), and the callbacks the AR host reports through (surface ready,
 * anchor, tracking, character events). The slot is remembered on (sprite, surface, debug, the request counters,
 * ability); its callbacks read the latest handlers through `rememberUpdatedState`, so HUD changes never re-create it. [capture] (T03) brings the
 * camera, the photo picker, the real pipeline, bundled characters and the metrics run; without it the Processing
 * screen runs the fake stage loop so the shell still demos.
 *
 * Scan (T04): the Scan / Rescan button bumps `ArSlot.scanRequest`; the host grabs one camera frame, segments it on
 * IO, swaps the new `Level` into physics and answers once through `onScanned` (count, or null on failure) and
 * `onLevel`. The overlay's arc runs toward [SCAN_ARC_HOLD] over `SCAN_DEFAULT_MS` and holds until that answer, so it
 * animates for the real duration. Without a host the shell has no AR view to answer, so the scan ends after
 * `SCAN_DEFAULT_MS` with an honest 0.
 *
 * Jump / Scenario (T20): the Play HUD's Jump / Fly button bumps `ArSlot.jumpRequest`, the debug Scenario button
 * (footprints on) bumps `ArSlot.scenarioRequest`; the slot also carries the sheet's ability. Both counters reset to 0
 * with `scanRequest` when the AR session ends. Offline (T20, review fix): [CaptureHost.offline] feeds the HUD badge, Capture's
 * note and the Scanning NO_PLANE hint; no button is disabled by it, because the guard only goes back online after a
 * successful call, so a tap must stay the retry.
 *
 * Voice (T24): with a [CaptureHost.voice] and the character placed, Play shows the hold-to-talk mic. Press ->
 * `startListening` (or `requestPermission` without RECORD_AUDIO); release -> `stopAndTranscribe(lang.code)`, then
 * `VoiceCommands.parse`: JUMP bumps `ArSlot.jumpRequest`, WALK bumps `ArSlot.walkForwardRequest` (reset with the
 * other counters). The transcript (or "Didn't catch that") shows for [VOICE_BUBBLE_MS] with a mic glyph. A result that
 * arrives after Play was left is dropped. The mic is the one control offline disables: a voice phrase is not a retry
 * (Scan is), and without the network it can only fail.
 *
 * [backHandler] wires the system back gesture: :androidApp passes
 * `{ enabled, onBack -> androidx.activity.compose.BackHandler(enabled, onBack) }` (predictive-back safe). :shared has
 * no back API of its own on Android without a new dependency (CMP's `ui-backhandler` is not on its classpath).
 * Back rule: Metrics -> where it came from; Play / Scanning / Processing -> Capture; Capture -> system (exit).
 */
@Composable
fun App(
    arView: @Composable (Modifier, ArSlot) -> Unit,
    capture: CaptureHost? = null,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    var screen by remember { mutableStateOf(Screen.CAPTURE) }
    var lang by remember { mutableStateOf(Lang.KK) }
    var sheet by remember { mutableStateOf(DemoData.sheet) }
    var processing by remember { mutableStateOf(ProcessingState()) }
    var scan by remember { mutableStateOf(ScanState()) }
    var hud by remember { mutableStateOf(HudState()) }
    var exportStatus by remember { mutableStateOf(ExportStatus.IDLE) }
    var exportLocation by remember { mutableStateOf<String?>(null) }
    var metricsRun by remember { mutableStateOf<MetricsRun?>(null) }
    var lastScreen by remember { mutableStateOf(Screen.CAPTURE) }
    // T03: the photo being turned into a character, the sprite Play shows (null = bundled default), and a counter
    // that restarts the pipeline effect (ProcessingState.attempt now follows the pipeline's own retries).
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var spritePng by remember { mutableStateOf<ByteArray?>(null) }
    var pipelineRun by remember { mutableStateOf(0) }
    var pendingResult by remember { mutableStateOf<CharacterResult?>(null) }
    // T13: the surface (kept across Back), the picked bundled card, what the character says, and the first walk.
    var surface by remember { mutableStateOf(Surface.TABLE) }
    var selectedBundled by remember { mutableStateOf<String?>(null) }
    var speech by remember { mutableStateOf(HudSpeech.State()) }
    var walkedOnce by remember { mutableStateOf(false) }
    // T04: one scan per increment (ArSlot.scanRequest); the level the host last built (for the mini-map).
    var scanRequest by remember { mutableStateOf(0) }
    var level by remember { mutableStateOf<Level?>(null) }
    // T20: one Physics.jump / one Scenarios.DAY5 replay per increment (ArSlot.jumpRequest / scenarioRequest).
    var jumpRequest by remember { mutableStateOf(0) }
    var scenarioRequest by remember { mutableStateOf(0) }
    // T24: push-to-talk phase, one walk step per increment (ArSlot.walkForwardRequest), the voice bubble's serial.
    var voicePhase by remember { mutableStateOf(VoicePhase.IDLE) }
    var walkForwardRequest by remember { mutableStateOf(0) }
    var voiceSerial by remember { mutableStateOf(-1) }

    val scope = rememberCoroutineScope()
    val clock = remember { TimeSource.Monotonic.markNow() }
    fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds
    val bundled = remember(capture) { capture?.bundled()?.takeIf { it.isNotEmpty() } ?: DemoData.bundled }
    val offlineFlow = remember(capture) { capture?.offline ?: MutableStateFlow(false) }
    val offline by offlineFlow.collectAsState()
    val voice = remember(capture) { capture?.voice }

    fun go(to: Screen) { lastScreen = screen; screen = to }
    fun startPipeline(bytes: ByteArray?) {
        if (bytes == null) return
        photo = bytes
        pendingResult = null
        selectedBundled = null
        processing = ProcessingState()
        pipelineRun++
        go(Screen.PROCESSING)
    }
    /** Processing -> Scanning with the character that was just made (sheet + sprite), or the demo one. */
    fun useResult() {
        val r = pendingResult
        sheet = r?.sheet ?: processing.sheet ?: DemoData.sheet
        spritePng = r?.sprite
        go(Screen.SCANNING)
    }
    /**
     * The one Back rule, shared by the on-screen Back buttons and the system back gesture: Metrics -> where it was
     * opened from; Play / Scanning / Processing -> Capture (leaves the AR session, cancels a running pipeline);
     * Capture -> not handled (the system exits).
     */
    fun back() {
        when (screen) {
            Screen.CAPTURE -> Unit
            Screen.METRICS -> {
                exportStatus = ExportStatus.IDLE
                screen = if (lastScreen == Screen.METRICS) Screen.CAPTURE else lastScreen
            }
            Screen.SCANNING -> { scan = ScanState(surface = surface); go(Screen.CAPTURE) }
            Screen.PROCESSING, Screen.PLAY -> go(Screen.CAPTURE)
        }
    }
    fun openMetrics() {
        metricsRun = if (capture != null) capture.latestRun() else DemoData.metrics
        exportStatus = ExportStatus.IDLE
        exportLocation = null
        go(Screen.METRICS)
    }
    /** T24 mic pressed: record, or ask for RECORD_AUDIO (the next press records). Never throws into the gesture. */
    fun voiceDown() {
        val v = voice ?: return
        if (voicePhase != VoicePhase.IDLE) return
        try {
            if (!v.hasPermission()) { v.requestPermission(); return }
            if (v.startListening()) voicePhase = VoicePhase.LISTENING
        } catch (e: Exception) {
            voicePhase = VoicePhase.IDLE // the contract says never throws; a host bug only leaves the mic idle
        }
    }
    /** T24 mic released: transcribe, parse, show what was heard, and bump the matching slot request. */
    fun voiceUp() {
        val v = voice ?: return
        if (voicePhase != VoicePhase.LISTENING) return
        voicePhase = VoicePhase.THINKING
        val spoken = lang
        scope.launch {
            val text = try {
                v.stopAndTranscribe(spoken.code)
            } catch (e: CancellationException) {
                voicePhase = VoicePhase.IDLE
                throw e
            } catch (e: Exception) {
                null
            }
            voicePhase = VoicePhase.IDLE
            if (screen != Screen.PLAY) return@launch // Play was left meanwhile: no stale jump in the next session
            val command = text?.let(VoiceCommands::parse)
            // A network failure already shows the offline pill and disables the mic; everything else gets an answer.
            val line = when {
                command != null && text != null -> text.trim()
                text == null && offlineFlow.value -> null
                else -> StringId.voice_unknown.get(lang)
            }
            if (line != null) {
                speech = HudSpeech.say(speech, line, nowMs())
                voiceSerial = speech.serial
            }
            when (command) {
                VoiceCommand.JUMP -> jumpRequest++
                VoiceCommand.WALK -> walkForwardRequest++
                null -> Unit
            }
        }
    }
    fun exportRun() {
        val host = capture ?: return
        if (exportStatus == ExportStatus.SAVING) return
        exportStatus = ExportStatus.SAVING
        scope.launch {
            val where = try {
                host.exportRun()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // the contract says never throws; a host bug still only shows "Not saved"
            }
            exportLocation = where
            exportStatus = if (where != null) ExportStatus.SAVED else ExportStatus.FAILED
        }
    }

    // ---- The AR slot: callbacks from the host, read through rememberUpdatedState so the slot stays stable. ----
    val onSurfaceReady = rememberUpdatedState<(Boolean) -> Unit> { ready -> scan = scan.copy(surfaceReady = ready) }
    val onAnchorPlaced = rememberUpdatedState<() -> Unit> {
        scan = scan.copy(anchored = true, surfaceReady = true)
        hud = hud.copy(showTapHint = true)
    }
    val onTracking = rememberUpdatedState<(Boolean) -> Unit> { ok ->
        hud = hud.copy(trackingLost = !ok)
        if (screen == Screen.SCANNING) {
            // Tracking lost during a scan cancels it (DESIGN.md "Scanning"); it is not retried automatically.
            if (!ok && scan.status != ScanStatus.PAUSED) scan = scan.copy(status = ScanStatus.PAUSED, progress = 0f)
            if (ok && scan.status == ScanStatus.PAUSED) scan = scan.copy(status = ScanStatus.IDLE)
        }
    }
    val onCharacterEvent = rememberUpdatedState<(CharacterEvent) -> Unit> { e ->
        if (e == CharacterEvent.STARTED_WALKING) walkedOnce = true
        speech = HudSpeech.next(speech, e, nowMs(), Strings.sheetLines(sheet.lines, lang), lang)
    }
    // Scanning: the real count -> DONE; 0 -> EMPTY (table found, nothing on it; not a failure); null -> NO_PLANE
    // (Skip appears after two). A late answer after tracking loss (PAUSED) or Back is ignored. Play: the Rescan
    // overlay ends; an empty rescan says "Table is empty", a failed one "No table found", so the HUD stays honest.
    val onScanned = rememberUpdatedState<(Int?) -> Unit> { objects ->
        when (screen) {
            Screen.SCANNING -> if (scan.status == ScanStatus.SCANNING) {
                scan = if (objects == null) {
                    scan.copy(status = ScanStatus.NO_PLANE, progress = 0f, objectsFound = 0, failures = scan.failures + 1)
                } else if (objects == 0) {
                    scan.copy(status = ScanStatus.EMPTY, progress = 0f, objectsFound = 0)
                } else {
                    scan.copy(status = ScanStatus.DONE, progress = 1f, objectsFound = objects, failures = 0)
                }
            }
            else -> {
                if (hud.scanning && (objects == null || objects == 0)) {
                    val floor = surface == Surface.FLOOR
                    val id = when {
                        objects == 0 -> if (floor) StringId.scan_empty_title_floor else StringId.scan_empty_title
                        else -> if (floor) StringId.scan_no_plane_title_floor else StringId.scan_no_plane_title
                    }
                    speech = HudSpeech.say(speech, id.get(lang), nowMs())
                }
                hud = hud.copy(scanning = false, scanProgress = 0f, scanned = hud.scanned || objects != null)
            }
        }
    }
    val onLevel = rememberUpdatedState<(Level) -> Unit> { level = it }
    val debug = hud.showFootprints
    val ability = sheet.ability
    val slot = remember(spritePng, surface, debug, scanRequest, jumpRequest, scenarioRequest, walkForwardRequest, ability) {
        ArSlot(
            spritePng = spritePng,
            surface = surface,
            debug = debug,
            onSurfaceReady = { onSurfaceReady.value(it) },
            onAnchorPlaced = { onAnchorPlaced.value() },
            onTracking = { onTracking.value(it) },
            onCharacterEvent = { onCharacterEvent.value(it) },
            scanRequest = scanRequest,
            onScanned = { onScanned.value(it) },
            onLevel = { onLevel.value(it) },
            ability = ability,
            jumpRequest = jumpRequest,
            scenarioRequest = scenarioRequest,
            walkForwardRequest = walkForwardRequest,
        )
    }
    val arOn = screen == Screen.SCANNING || screen == Screen.PLAY || (screen == Screen.METRICS && lastScreen == Screen.PLAY)
    val noAr: @Composable (Modifier) -> Unit = {}

    // The session ends when the slot leaves: the next one starts from "look at the table" again.
    LaunchedEffect(arOn) {
        if (!arOn) {
            scan = ScanState(surface = surface)
            hud = HudState(showFootprints = hud.showFootprints)
            speech = HudSpeech.State()
            walkedOnce = false
            level = null
            scanRequest = 0 // a new session's first composition must not see a stale request
            jumpRequest = 0
            scenarioRequest = 0
            walkForwardRequest = 0
        }
    }
    LaunchedEffect(walkedOnce) {
        if (walkedOnce) { delay(TAP_HINT_AFTER_WALK_MS); hud = hud.copy(showTapHint = false) }
    }
    // "Back up" queued behind a fresh "Oops! Fell" (HudSpeech.FELL_HOLD_MS).
    LaunchedEffect(speech.pendingAtMs) {
        val at = speech.pendingAtMs ?: return@LaunchedEffect
        delay((at - nowMs()).coerceAtLeast(0))
        speech = HudSpeech.tick(speech, nowMs())
    }
    LaunchedEffect(speech.serial) {
        if (speech.text != null) {
            delay(if (speech.serial == voiceSerial) VOICE_BUBBLE_MS else RudeusMotion.BUBBLE_VISIBLE_MS)
            speech = HudSpeech.dismissed(speech)
        }
    }

    // System back / predictive back gesture: same rule as the Back buttons; on Capture the system handles it (exit).
    backHandler(screen != Screen.CAPTURE, ::back)

    RudeusTheme {
        CompositionLocalProvider(LocalLang provides lang) {
            Box(Modifier.fillMaxSize()) {
                // The single AR call site (T13 (a)): same position for Scanning, Play and Metrics-over-Play.
                if (arOn) arView(Modifier.fillMaxSize(), slot)

                when (screen) {
                    Screen.CAPTURE -> CaptureScreen(
                        bundled = bundled,
                        offline = offline,
                        lang = lang,
                        onLangChange = { lang = it },
                        onTakePhoto = {
                            if (capture != null) capture.takePhoto(::startPipeline) else startPipeline(ByteArray(0))
                        },
                        onPickPhoto = capture?.let { host -> { host.pickPhoto(::startPipeline) } },
                        onPickBundled = { c: BundledCharacter ->
                            sheet = c.sheet
                            spritePng = c.spritePng // null keeps the host's built-in dino
                            selectedBundled = c.id
                            go(Screen.SCANNING)
                        },
                        onOpenMetrics = ::openMetrics,
                        selectedId = selectedBundled,
                    )

                    Screen.PROCESSING -> {
                        LaunchedEffect(pipelineRun) {
                            val run = pipelineRun
                            val bytes = photo
                            if (capture == null || bytes == null) {
                                // Demo shell without a host: advances one stage per 900 ms.
                                var done = emptySet<PipelineStage>()
                                for (stage in PipelineStage.entries) {
                                    processing = processing.copy(stage = stage, completed = done, status = ProcessingStatus.RUNNING)
                                    delay(900)
                                    done = done + stage
                                    if (stage == PipelineStage.GATE) processing = processing.copy(gateScore = 0.82f)
                                }
                                processing = processing.copy(stage = null, completed = done, status = ProcessingStatus.DONE, sheet = DemoData.sheet)
                            } else {
                                // Real pipeline (T03). Events arrive on the pipeline's thread; each one is a single
                                // snapshot-state write, so no dispatch is needed. The result is taken from the Finished
                                // event, so it is in place before the screen shows "Next". Leaving the screen cancels the run.
                                try {
                                    capture.makeCharacter(bytes) { event ->
                                        // After Retry a late event of the cancelled run must not fold into the fresh state.
                                        if (run != pipelineRun) return@makeCharacter
                                        if (event is PipelineEvent.Finished) pendingResult = event.result
                                        processing = processing.reduce(event)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // The pipeline already emitted Failed; this covers a missing key and host errors.
                                    if (run == pipelineRun && processing.status != ProcessingStatus.ERROR) processing = processing.copy(status = ProcessingStatus.ERROR)
                                }
                            }
                        }
                        ProcessingScreen(
                            state = processing,
                            onRetry = { processing = ProcessingState(); pendingResult = null; pipelineRun++ },
                            onShipAnyway = ::useResult,
                            onContinue = ::useResult,
                            onBack = ::back,
                            sprite = pendingResult?.sprite,
                        )
                    }

                    Screen.SCANNING -> {
                        // The arc runs while the host scans (its answer arrives through onScanned); the host-less
                        // shell has nobody to answer, so it ends after SCAN_DEFAULT_MS with an honest 0.
                        LaunchedEffect(scan.status) {
                            if (scan.status == ScanStatus.SCANNING) {
                                val t0 = nowMs()
                                while (true) {
                                    delay(SCAN_ARC_TICK_MS)
                                    val elapsed = nowMs() - t0
                                    scan = scan.copy(progress = (elapsed.toFloat() / RudeusMotion.SCAN_DEFAULT_MS).coerceAtMost(SCAN_ARC_HOLD))
                                    if (capture == null && elapsed >= RudeusMotion.SCAN_DEFAULT_MS) {
                                        scan = scan.copy(status = ScanStatus.EMPTY, progress = 0f, objectsFound = 0)
                                        break
                                    }
                                }
                            } else if (scan.status == ScanStatus.DONE) {
                                delay(1500)
                                // The AR session continues into Play: keep surfaceReady/anchored, only the scan ends.
                                scan = scan.copy(status = ScanStatus.IDLE, progress = 0f)
                                hud = hud.copy(scanned = true)
                                go(Screen.PLAY)
                            }
                        }
                        ScanningScreen(
                            state = scan.copy(surface = surface, offline = offline),
                            arView = noAr,
                            // Offline is never a lock: the request still goes out (a success flips the flag back) and a
                            // failure lands on the NO_PLANE card, which names the network while offline.
                            onScan = { scan = scan.copy(status = ScanStatus.SCANNING, progress = 0f); scanRequest++ },
                            onSkip = {
                                // "Play anyway" after a real empty scan: the table was scanned, so Play says "Rescan".
                                if (scan.status == ScanStatus.EMPTY) hud = hud.copy(scanned = true)
                                scan = scan.copy(status = ScanStatus.IDLE, progress = 0f); go(Screen.PLAY)
                            },
                            onBack = ::back,
                            onSurfaceChange = { surface = it },
                            speech = speech.text,
                        )
                    }

                    Screen.PLAY -> {
                        // T03 gate: shutter -> Play. Logged by the host (RudeusGate capture_to_play_ms) once per entry
                        // from Scanning (coming back from Metrics is not a new entry).
                        LaunchedEffect(Unit) { if (spritePng != null && lastScreen == Screen.SCANNING) capture?.playEntered() }
                        // Rescan: same arc as Scanning, ended by the host's onScanned (or the timer without a host).
                        LaunchedEffect(hud.scanning) {
                            if (hud.scanning) {
                                val t0 = nowMs()
                                while (true) {
                                    delay(SCAN_ARC_TICK_MS)
                                    val elapsed = nowMs() - t0
                                    hud = hud.copy(scanProgress = (elapsed.toFloat() / RudeusMotion.SCAN_DEFAULT_MS).coerceAtMost(SCAN_ARC_HOLD))
                                    if (capture == null && elapsed >= RudeusMotion.SCAN_DEFAULT_MS) {
                                        hud = hud.copy(scanning = false, scanProgress = 0f, scanned = true)
                                        break
                                    }
                                }
                            }
                        }
                        PlayScreen(
                            arView = noAr,
                            // Minimal until the host reports physics state: the ability is the character's own. The
                            // level is the host's last scan (ArSlot.onLevel); the mini-map draws it with footprints on.
                            character = CharacterState(ability = sheet.ability),
                            sheet = sheet,
                            level = level,
                            hud = hud.copy(
                                speech = speech.text,
                                surface = surface,
                                offline = offline,
                                voice = voicePhase,
                                speechFromVoice = speech.text != null && speech.serial == voiceSerial,
                            ),
                            onScan = { hud = hud.copy(scanning = true, scanProgress = 0f, showTapHint = false); scanRequest++ },
                            onJump = { jumpRequest++ },
                            onToggleFootprints = { hud = hud.copy(showFootprints = !hud.showFootprints) },
                            onAbilityTap = {
                                val lines = Strings.sheetLines(sheet.lines, lang)
                                val line = lines.getOrNull(1) ?: lines.firstOrNull()
                                if (line != null) speech = HudSpeech.say(speech, line, nowMs())
                            },
                            onOpenMetrics = ::openMetrics,
                            onBack = ::back,
                            onScenario = if (hud.showFootprints) ({ scenarioRequest++ }) else null,
                            onVoiceDown = if (voice != null && scan.anchored) ::voiceDown else null,
                            onVoiceUp = ::voiceUp,
                        )
                    }

                    // Over Play the AR session keeps running underneath; the paper screen covers it and takes the touches.
                    Screen.METRICS -> MetricsScreen(
                        run = metricsRun,
                        exportStatus = exportStatus,
                        exportLocation = exportLocation,
                        canExport = capture != null,
                        onExportJson = ::exportRun,
                        onBack = ::back,
                        modifier = Modifier.swallowTouches(),
                    )
                }
            }
        }
    }
}

/** Keeps taps on a full-screen paper screen from reaching the AR view composed underneath it. */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/** Demo data so the shell runs before the pipeline, scan and physics are wired. */
internal object DemoData {
    val sheet = CharacterSheet(
        name = "Мұрат",
        personality = "batyl, kүлегеш",
        ability = Ability.JUMP,
        lines = mapOf(
            "kk" to listOf("Сәлем! Мен Мұратпын!", "Секіремін!", "Ой, кесе!", "Кітапқа шығамын!", "Қайда барамыз?"),
            "ru" to listOf("Привет! Я Мурат!", "Прыгаю!", "Ой, чашка!", "Залезу на книгу!", "Куда пойдём?"),
        ),
    )

    val bundled = listOf(
        BundledCharacter("demo-murat", sheet),
        BundledCharacter(
            "demo-koshka",
            CharacterSheet(
                name = "Мысық",
                personality = "жалқау",
                ability = Ability.HIDE,
                lines = mapOf(
                    "kk" to listOf("Мияу!", "Ұйықтағым келеді", "Тығыламын", "Кесе артында!", "Тапшы мені"),
                    "ru" to listOf("Мяу!", "Хочу спать", "Прячусь", "За чашкой!", "Найди меня"),
                ),
            ),
        ),
    )


    private val demoCharacters = listOf(
        CharacterMetrics("drawings/cat-01.jpg", emptyList(), 0.82f, true, 0, 31_200, 0.031),
        CharacterMetrics("drawings/robot-02.jpg", emptyList(), 0.64f, false, 2, 58_900, 0.071),
        CharacterMetrics("toys/dino.jpg", emptyList(), 0.91f, true, 0, 27_400, 0.029),
    )

    /** The host-less shell's Metrics screen: a fixed demo run in core's schema (the summary is computed, not typed). */
    val metrics = MetricsRun(
        runId = "2026-10-28T14-03-22Z",
        source = "app",
        provider = "fixture",
        device = "Pixel 8",
        characters = demoCharacters,
        scans = listOf(ScanMetrics(5, 4800, listOf(1.2f, 0.8f, 2.1f, 1.5f, 0.9f))),
        summary = MetricsLog.summaryOf(demoCharacters),
    )
}
