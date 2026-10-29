package kz.rudy.ar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.launch
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.metrics.CharacterMetrics
import kz.rudy.ar.core.metrics.MetricsLog
import kz.rudy.ar.core.metrics.MetricsRun
import kz.rudy.ar.core.metrics.ScanMetrics
import kz.rudy.ar.core.pipeline.CharacterResult
import kz.rudy.ar.core.pipeline.PipelineEvent
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.LocalLang
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
import kz.rudy.ar.ui.screens.reduce
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusTheme
import kotlin.time.TimeSource

enum class Screen { CAPTURE, PROCESSING, SCANNING, PLAY, METRICS }

/** How long the "Tap — it walks" pill stays after the first walk (DESIGN.md "Play"). */
private const val TAP_HINT_AFTER_WALK_MS = 5000L

/**
 * Minimal navigation shell. :androidApp calls
 * `App(arView = { modifier, slot -> RudeusArView(modifier, slot) }, capture = host)`.
 *
 * One AR session: [arView] is composed exactly once, at one call site in a `Box` under every screen, for as long as
 * the screen is Scanning or Play (and while Metrics is opened from Play, drawn on top of it), so the ARCore session
 * and its single anchor survive Scanning -> Play. Going to Capture (or Metrics from Capture) disposes it; the next
 * Scanning starts a new session. Screens get a no-op `arView`.
 *
 * [arView]'s second argument is the [ArSlot]: the sprite Play shows (captured or bundled, null = the host's default),
 * the chosen [Surface], the debug flag (footprints on), and the callbacks the AR host reports through (surface ready,
 * anchor, tracking, character events). The slot is remembered on (sprite, surface, debug); its callbacks read the
 * latest handlers through `rememberUpdatedState`, so HUD changes never re-create it. [capture] (T03) brings the
 * camera, the photo picker, the real pipeline, bundled characters and the metrics run; without it the Processing
 * screen runs the fake stage loop so the shell still demos. Scan (T04) is still a fake timer that finds 0 objects.
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

    val scope = rememberCoroutineScope()
    val clock = remember { TimeSource.Monotonic.markNow() }
    fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds
    val bundled = remember(capture) { capture?.bundled()?.takeIf { it.isNotEmpty() } ?: DemoData.bundled }

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
    val debug = hud.showFootprints
    val slot = remember(spritePng, surface, debug) {
        ArSlot(
            spritePng = spritePng,
            surface = surface,
            debug = debug,
            onSurfaceReady = { onSurfaceReady.value(it) },
            onAnchorPlaced = { onAnchorPlaced.value() },
            onTracking = { onTracking.value(it) },
            onCharacterEvent = { onCharacterEvent.value(it) },
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
        if (speech.text != null) { delay(RudeusMotion.BUBBLE_VISIBLE_MS); speech = HudSpeech.dismissed(speech) }
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
                        offline = false,
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
                        // Fake scan until T04: the looking-around animation runs SCAN_DEFAULT_MS and honestly finds 0 objects.
                        LaunchedEffect(scan.status) {
                            if (scan.status == ScanStatus.SCANNING) {
                                val steps = 40
                                repeat(steps) { i ->
                                    delay((RudeusMotion.SCAN_DEFAULT_MS / steps).toLong())
                                    scan = scan.copy(progress = (i + 1) / steps.toFloat())
                                }
                                scan = scan.copy(status = ScanStatus.DONE, objectsFound = 0)
                            } else if (scan.status == ScanStatus.DONE) {
                                delay(1500)
                                // The AR session continues into Play: keep surfaceReady/anchored, only the scan ends.
                                scan = scan.copy(status = ScanStatus.IDLE, progress = 0f)
                                hud = hud.copy(scanned = true)
                                go(Screen.PLAY)
                            }
                        }
                        ScanningScreen(
                            state = scan.copy(surface = surface),
                            arView = noAr,
                            onScan = { scan = scan.copy(status = ScanStatus.SCANNING, progress = 0f) },
                            onSkip = { scan = scan.copy(status = ScanStatus.IDLE, progress = 0f); go(Screen.PLAY) },
                            onBack = ::back,
                            onSurfaceChange = { surface = it },
                            speech = speech.text,
                        )
                    }

                    Screen.PLAY -> {
                        // T03 gate: shutter -> Play. Logged by the host (RudeusGate capture_to_play_ms) once per entry
                        // from Scanning (coming back from Metrics is not a new entry).
                        LaunchedEffect(Unit) { if (spritePng != null && lastScreen == Screen.SCANNING) capture?.playEntered() }
                        LaunchedEffect(hud.scanning) {
                            if (hud.scanning) {
                                val steps = 40
                                repeat(steps) { i ->
                                    delay((RudeusMotion.SCAN_DEFAULT_MS / steps).toLong())
                                    hud = hud.copy(scanProgress = (i + 1) / steps.toFloat())
                                }
                                hud = hud.copy(scanning = false, scanProgress = 0f, scanned = true)
                            }
                        }
                        PlayScreen(
                            arView = noAr,
                            // Minimal until the host reports physics state: the ability is the character's own; no
                            // level until the real scan (T04) — the fake scan found nothing, so footprints show nothing.
                            character = CharacterState(ability = sheet.ability),
                            sheet = sheet,
                            level = null,
                            hud = hud.copy(speech = speech.text, surface = surface),
                            onScan = { hud = hud.copy(scanning = true, scanProgress = 0f, showTapHint = false) },
                            onToggleFootprints = { hud = hud.copy(showFootprints = !hud.showFootprints) },
                            onAbilityTap = {
                                val lines = Strings.sheetLines(sheet.lines, lang)
                                val line = lines.getOrNull(1) ?: lines.firstOrNull()
                                if (line != null) speech = HudSpeech.say(speech, line, nowMs())
                            },
                            onOpenMetrics = ::openMetrics,
                            onBack = ::back,
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
