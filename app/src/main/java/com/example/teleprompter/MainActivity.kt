package com.example.teleprompter

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.teleprompter.data.ScriptImporter
import com.example.teleprompter.engine.TeleprompterSession
import com.example.teleprompter.overlay.OverlayService
import com.example.teleprompter.ui.RecordingScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { AppRoot() } } }
    }

    /** Bluetooth keyboard / presentation clicker mapping (while this app is in front). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = TeleprompterSession
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> s.togglePlay()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> s.adjustWpm(+10)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> s.adjustWpm(-10)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = TeleprompterSession
    val text by s.text.collectAsState()
    val voice by s.voiceFollow.collectAsState()
    var studio by remember { mutableStateOf(false) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { scope.launch { s.text.value = ScriptImporter(ctx).import(it) } }
    }
    LaunchedEffect(Unit) {
        val p = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(p.toTypedArray())
    }

    if (studio) { RecordingScreen(onBack = { studio = false }); return }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { s.text.value = it },
            label = { Text("Script (use ==word== to highlight)") },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(voice, { s.voiceFollow.value = it }); Spacer(Modifier.width(8.dp)); Text("Voice follow")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ picker.launch("*/*") }) { Text("Import") }
            Button({
                if (!Settings.canDrawOverlays(ctx)) ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                ) else OverlayService.start(ctx)
            }) { Text("Overlay") }
            Button({ studio = true }) { Text("Studio") }
        }
    }
}
