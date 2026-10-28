package com.example.teleprompter.ui

import android.app.Application
import androidx.camera.video.Quality
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.teleprompter.camera.CameraRecorderManager
import com.example.teleprompter.camera.RecState
import com.example.teleprompter.engine.TeleprompterSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecordingViewModel(app: Application) : AndroidViewModel(app) {
    val recorder = CameraRecorderManager(app)
    private val _countdown = MutableStateFlow(0)
    val countdown = _countdown.asStateFlow()
    private var job: Job? = null

    /** 3-2-1, then record and start scrolling together. */
    fun startWithCountdown(seconds: Int = 3) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            for (i in seconds downTo 1) { _countdown.value = i; delay(1000) }
            _countdown.value = 0
            recorder.start()
            TeleprompterSession.isPlaying.value = true
        }
    }

    fun stop() {
        job?.cancel(); _countdown.value = 0
        recorder.stop()
        TeleprompterSession.isPlaying.value = false
    }

    override fun onCleared() = recorder.release()
}

@Composable
fun RecordingScreen(onBack: () -> Unit, vm: RecordingViewModel = viewModel()) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val s = TeleprompterSession
    val text by s.text.collectAsState()
    val style by s.style.collectAsState()
    val playing by s.isPlaying.collectAsState()
    val wpm by s.wpm.collectAsState()
    val voice by s.voiceFollow.collectAsState()
    val speaking by s.isSpeaking.collectAsState()
    val matched by s.matchedWord.collectAsState()
    val recState by vm.recorder.state.collectAsState()
    val wave by vm.recorder.wave.collectAsState()
    val countdown by vm.countdown.collectAsState()

    var front by remember { mutableStateOf(true) }
    var uhd by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    LaunchedEffect(front, uhd) {
        vm.recorder.bind(owner, previewView, front, if (uhd) Quality.UHD else Quality.FHD)
    }
    val recording = recState is RecState.Recording

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())

        // Reading zone hugging the top edge, right under the selfie lens.
        AutoScrollTeleprompter(
            text, style.copy(bgAlpha = 0.45f), playing, wpm, voice, speaking, matched,
            Modifier.fillMaxWidth().fillMaxHeight(0.33f).align(Alignment.TopCenter)
        )

        if (countdown > 0) {
            Text("$countdown", fontSize = 120.sp, color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            Waveform(wave, Modifier.fillMaxWidth().height(40.dp))
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                IconButton({ front = !front }, enabled = !recording) {
                    Icon(Icons.Default.FlipCameraAndroid, "Flip", tint = Color.White)
                }
                FilledIconButton(
                    onClick = { if (recording || countdown > 0) vm.stop() else vm.startWithCountdown() },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Red)
                ) {
                    Icon(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, "Record", tint = Color.White)
                }
                IconButton({ muted = !muted; vm.recorder.setMuted(muted) }) {
                    Icon(if (muted) Icons.Default.MicOff else Icons.Default.Mic, "Mute", tint = Color.White)
                }
                TextButton({ uhd = !uhd }, enabled = !recording) { Text(if (uhd) "4K" else "1080p", color = Color.White) }
            }
        }
    }
}

@Composable
fun Waveform(samples: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val step = size.width / 60f
        samples.forEachIndexed { i, a ->
            val h = (a * size.height).coerceAtLeast(3f)
            val x = i * step
            drawLine(Color(0xFF66BB6A), Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = step * 0.6f)
        }
    }
}
