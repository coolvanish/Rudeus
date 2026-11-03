package kz.rudy.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import kz.rudy.ar.ar.RudeusArView
import kz.rudy.ar.audio.Sounds
import kz.rudy.ar.capture.rememberPhotoIntake
import kz.rudy.ar.di.AndroidCaptureHost
import kz.rudy.ar.di.BundledAssets
import kz.rudy.ar.ui.screens.PermissionScreen
import kz.rudy.ar.ui.theme.LocalReducedMotion

// HAND-REVIEW: AR host entry point. The AR view is passed into the shared App() as a slot composable.
// HAND-REVIEW: T03 — App gets the CaptureHost (camera + picker launchers, MetaApi pipeline, metrics). The launchers
// must be registered in composition, hence rememberPhotoIntake() here; the host is remembered with them so one
// MetricsLog spans the session.
// HAND-REVIEW: T14 — the slot composable receives the whole ArSlot (sprite, surface, HUD callbacks); the permission
// gate shows the shared PermissionScreen (paper + one button) instead of a bare Material button.
// HAND-REVIEW: T04 — the same host object is the AR view's TableScanner (SAM call + scan metrics stay in :androidApp;
// :shared only sees ArSlot.scanRequest / onScanned / onLevel).
// HAND-REVIEW: T24 — a RECORD_AUDIO RequestPermission launcher, registered in composition like the photo ones, is
// handed to the host as its `requestMic`; the host is the Play HUD's VoiceHost (the mic button asks on first press).
class MainActivity : ComponentActivity() {
    // HAND-REVIEW: T26 —the system "Animator duration scale = off" reaches App as LocalReducedMotion (App ORs it with
    // the Settings toggle). Re-read on every resume: changing it in system settings does not recreate the activity.
    // A new value only recomposes; the provider stays at the same call site, so the ARCore session is kept.
    private var systemReducedMotion by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        systemReducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        android.util.Log.i("RudeusGate", "reduced_motion_host=$systemReducedMotion")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // HAND-REVIEW: review fix — the 7.6 MB bundled set is parsed on a background thread from here on, so the
        // first composition's `bundled()` only waits for whatever is left (BundledAssets' lazy is synchronized).
        BundledAssets.preload(applicationContext)
        setContent {
            val intake = rememberPhotoIntake()
            val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                android.util.Log.i("RudeusGate", "record_audio_granted=$granted")
            }
            val host = remember(intake) {
                AndroidCaptureHost(applicationContext, intake, requestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) })
            }
            val sounds = remember { Sounds(applicationContext) }
            DisposableEffect(Unit) { onDispose { sounds.release() } }
            CompositionLocalProvider(LocalReducedMotion provides systemReducedMotion) {
                App(
                    arView = { modifier, slot ->
                        // HAND-REVIEW: T23 — sounds ride on the slot's event callback; the AR view is unchanged.
                        // HAND-REVIEW: T26 —slot.soundsOn gates only the player; the event still reaches the HUD. A
                        // new slot does not recreate the session (RudeusArView reads it through rememberUpdatedState).
                        val withSound = remember(slot) {
                            slot.copy(onCharacterEvent = { e -> if (slot.soundsOn) sounds.play(e); slot.onCharacterEvent(e) })
                        }
                        LaunchedEffect(slot.soundsOn) { android.util.Log.i("RudeusGate", "sounds_on=${slot.soundsOn}") }
                        WithCameraPermission(modifier) { RudeusArView(modifier, withSound, scanner = host) }
                    },
                    // HAND-REVIEW: system/predictive Back goes through App's one back() rule (Metrics -> where it came
                    // from, Play/Scanning/Processing -> Capture); :shared has no activity-compose, so the host lends it.
                    backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
                    capture = host,
                )
            }
        }
    }
}

@Composable
private fun WithCameraPermission(modifier: Modifier, content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) {
        content()
    } else {
        PermissionScreen(onAllow = { launcher.launch(Manifest.permission.CAMERA) }, modifier = modifier)
    }
}
