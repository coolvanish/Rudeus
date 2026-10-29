package kz.rudy.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import kz.rudy.ar.ar.RudeusArView
import kz.rudy.ar.capture.rememberPhotoIntake
import kz.rudy.ar.di.AndroidCaptureHost
import kz.rudy.ar.ui.screens.PermissionScreen

// HAND-REVIEW: AR host entry point. The AR view is passed into the shared App() as a slot composable.
// HAND-REVIEW: T03 — App gets the CaptureHost (camera + picker launchers, MetaApi pipeline, metrics). The launchers
// must be registered in composition, hence rememberPhotoIntake() here; the host is remembered with them so one
// MetricsLog spans the session.
// HAND-REVIEW: T14 — the slot composable receives the whole ArSlot (sprite, surface, HUD callbacks); the permission
// gate shows the shared PermissionScreen (paper + one button) instead of a bare Material button.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val intake = rememberPhotoIntake()
            val host = remember(intake) { AndroidCaptureHost(applicationContext, intake) }
            App(
                arView = { modifier, slot ->
                    WithCameraPermission(modifier) { RudeusArView(modifier, slot) }
                },
                capture = host,
            )
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
