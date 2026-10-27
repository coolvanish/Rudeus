package kz.rudy.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.LocalLang
import kz.rudy.ar.ui.Strings
import kz.rudy.ar.ui.screens.BundledCharacter
import kz.rudy.ar.ui.screens.CaptureScreen
import kz.rudy.ar.ui.screens.ExportStatus
import kz.rudy.ar.ui.screens.HudState
import kz.rudy.ar.ui.screens.MetricsRunView
import kz.rudy.ar.ui.screens.MetricsScreen
import kz.rudy.ar.ui.screens.PipelineStage
import kz.rudy.ar.ui.screens.PlayScreen
import kz.rudy.ar.ui.screens.ProcessingScreen
import kz.rudy.ar.ui.screens.ProcessingState
import kz.rudy.ar.ui.screens.ProcessingStatus
import kz.rudy.ar.ui.screens.ScanState
import kz.rudy.ar.ui.screens.ScanStatus
import kz.rudy.ar.ui.screens.ScanningScreen
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusTheme

enum class Screen { CAPTURE, PROCESSING, SCANNING, PLAY, METRICS }

/**
 * Minimal navigation shell. :androidApp calls `App(arView = { modifier -> RudeusArView(modifier) })`.
 * Fake data for now; the pipeline, scan and physics wiring replace the LaunchedEffect stubs below.
 */
@Composable
fun App(arView: @Composable (Modifier) -> Unit) {
    var screen by remember { mutableStateOf(Screen.CAPTURE) }
    var lang by remember { mutableStateOf(Lang.KK) }
    var sheet by remember { mutableStateOf(DemoData.sheet) }
    var processing by remember { mutableStateOf(ProcessingState()) }
    var scan by remember { mutableStateOf(ScanState()) }
    var hud by remember { mutableStateOf(HudState()) }
    var exportStatus by remember { mutableStateOf(ExportStatus.IDLE) }
    var lastScreen by remember { mutableStateOf(Screen.CAPTURE) }

    fun go(to: Screen) { lastScreen = screen; screen = to }

    RudeusTheme {
        CompositionLocalProvider(LocalLang provides lang) {
            when (screen) {
                Screen.CAPTURE -> CaptureScreen(
                    bundled = DemoData.bundled,
                    offline = false,
                    lang = lang,
                    onLangChange = { lang = it },
                    onTakePhoto = { processing = ProcessingState(); go(Screen.PROCESSING) },
                    onPickBundled = { sheet = it.sheet; go(Screen.SCANNING) },
                    onOpenMetrics = { go(Screen.METRICS) },
                )

                Screen.PROCESSING -> {
                    // Fake pipeline: advances one stage per 900 ms. TODO(core): drive from CharacterPipeline events.
                    LaunchedEffect(processing.attempt) {
                        var done = emptySet<PipelineStage>()
                        for (stage in PipelineStage.entries) {
                            processing = processing.copy(stage = stage, completed = done, status = ProcessingStatus.RUNNING)
                            delay(900)
                            done = done + stage
                            if (stage == PipelineStage.GATE) processing = processing.copy(gateScore = 0.82f)
                        }
                        processing = processing.copy(stage = null, completed = done, status = ProcessingStatus.DONE, sheet = DemoData.sheet)
                    }
                    ProcessingScreen(
                        state = processing,
                        onRetry = { processing = ProcessingState(attempt = processing.attempt + 1) },
                        onShipAnyway = { sheet = processing.sheet ?: DemoData.sheet; go(Screen.SCANNING) },
                        onContinue = { sheet = processing.sheet ?: DemoData.sheet; go(Screen.SCANNING) },
                        onBack = { go(Screen.CAPTURE) },
                    )
                }

                Screen.SCANNING -> {
                    // Fake scan: the looking-around animation runs SCAN_DEFAULT_MS, then "5 objects". TODO(androidApp): real scan.
                    LaunchedEffect(scan.status) {
                        if (scan.status == ScanStatus.SCANNING) {
                            val steps = 40
                            repeat(steps) { i ->
                                delay((RudeusMotion.SCAN_DEFAULT_MS / steps).toLong())
                                scan = scan.copy(progress = (i + 1) / steps.toFloat())
                            }
                            scan = scan.copy(status = ScanStatus.DONE, objectsFound = DemoData.level.objects.size)
                        } else if (scan.status == ScanStatus.DONE) {
                            delay(1500)
                            scan = ScanState()
                            hud = HudState(speech = Strings.sheetLines(sheet.lines, lang).firstOrNull())
                            go(Screen.PLAY)
                        }
                    }
                    ScanningScreen(
                        state = scan,
                        arView = arView,
                        onScan = { scan = scan.copy(status = ScanStatus.SCANNING, progress = 0f) },
                        onSkip = { scan = ScanState(); go(Screen.PLAY) },
                        onBack = { scan = ScanState(); go(Screen.CAPTURE) },
                    )
                }

                Screen.PLAY -> {
                    LaunchedEffect(hud.speech) {
                        if (hud.speech != null) { delay(RudeusMotion.BUBBLE_VISIBLE_MS); hud = hud.copy(speech = null) }
                    }
                    LaunchedEffect(hud.scanning) {
                        if (hud.scanning) {
                            val steps = 40
                            repeat(steps) { i ->
                                delay((RudeusMotion.SCAN_DEFAULT_MS / steps).toLong())
                                hud = hud.copy(scanProgress = (i + 1) / steps.toFloat())
                            }
                            hud = hud.copy(scanning = false, scanProgress = 0f)
                        }
                    }
                    PlayScreen(
                        arView = arView,
                        character = DemoData.character,
                        sheet = sheet,
                        level = DemoData.level,
                        hud = hud,
                        onScan = { hud = hud.copy(scanning = true, scanProgress = 0f, showTapHint = false) },
                        onToggleFootprints = { hud = hud.copy(showFootprints = !hud.showFootprints) },
                        onAbilityTap = {
                            val lines = Strings.sheetLines(sheet.lines, lang)
                            hud = hud.copy(speech = lines.getOrNull(1) ?: lines.firstOrNull(), showTapHint = false)
                        },
                        onOpenMetrics = { go(Screen.METRICS) },
                        onBack = { go(Screen.CAPTURE) },
                    )
                }

                Screen.METRICS -> MetricsScreen(
                    run = DemoData.metrics,
                    exportStatus = exportStatus,
                    onExportJson = { exportStatus = ExportStatus.SAVED }, // TODO(androidApp): write runs/<ts>.json to app files.
                    onBack = { exportStatus = ExportStatus.IDLE; screen = if (lastScreen == Screen.METRICS) Screen.CAPTURE else lastScreen },
                )
            }
        }
    }
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

    val level = Level(
        objects = listOf(
            LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.circle(Vec2(0.12f, 0.05f), 0.04f), 0.09f),
            LevelObject("book-1", "book", ObjectKind.PLATFORM, Polygon.rect(Vec2(-0.1f, -0.08f), 0.18f, 0.12f), 0.025f),
            LevelObject("hand-1", "hand", ObjectKind.DYNAMIC, Polygon.rect(Vec2(0.0f, 0.18f), 0.08f, 0.14f), 0.03f),
        ),
        boundary = Polygon.rect(Vec2.ZERO, 0.6f, 0.5f),
        spawn = Vec2.ZERO,
    )

    val character = CharacterState(pos = Vec2(0.02f, 0.01f), ability = Ability.JUMP)

    val metrics = MetricsRunView(
        runId = "2026-10-28T14-03-22Z",
        source = "app",
        provider = "fixture",
        device = "Pixel 8",
        characters = listOf(
            MetricsRunView.CharacterRow("drawings/cat-01.jpg", 0.82f, true, 0, 31_200, 0.031),
            MetricsRunView.CharacterRow("drawings/robot-02.jpg", 0.64f, false, 2, 58_900, 0.071),
            MetricsRunView.CharacterRow("toys/dino.jpg", 0.91f, true, 0, 27_400, 0.029),
        ),
        scans = listOf(MetricsRunView.ScanRow(5, 4800, listOf(1.2f, 0.8f, 2.1f, 1.5f, 0.9f))),
        summary = MetricsRunView.SummaryView(3, 0.67, 31_200, 58_900, 0.044),
    )
}
