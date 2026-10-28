package com.example.teleprompter.ui

import android.app.Application
import androidx.camera.video.Quality
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.teleprompter.engine.VoiceFollowController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecordingViewModel(app: Application) : AndroidViewModel(app) {
    val recorder = CameraRecorderManager(app)
    private val voiceController = VoiceFollowController(app, viewModelScope)
    private val _countdown = MutableStateFlow(0)
    val countdown = _countdown.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { TeleprompterSession.text.collect { voiceController.setScript(it) } }
        viewModelScope.launch {
            TeleprompterSession.voiceFollow.collect { enabled ->
                if (enabled) voiceController.start() else voiceController.stop()
            }
        }
    }

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

    override fun onCleared() {
        voiceController.stop()
        recorder.release()
    }
}

@Composable
fun RecordingScreen(onBack: () -> Unit, cameraEnabled: Boolean = true, vm: RecordingViewModel = viewModel()) {
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

    LaunchedEffect(front, uhd, cameraEnabled) {
        if (cameraEnabled) vm.recorder.bind(owner, previewView, front, if (uhd) Quality.UHD else Quality.FHD)
        else vm.recorder.release()
    }
    val recording = recState is RecState.Recording

    Box(Modifier.fillMaxSize()) {
        if (cameraEnabled) AndroidView({ previewView }, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(Color(0xFF08090D)))

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = Color(0xCC08090D), shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = Color(0xFFFFC857), fontSize = 11.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(if (cameraEnabled) "EYE-LINE  •  CAMERA LENS" else "TEXT-ONLY PROMPTER", color = Color.White, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 0.6.sp)
                }
            }
            Surface(color = Color(0xCC08090D), shape = RoundedCornerShape(50)) {
                Text("$wpm WPM", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color(0xFF00E5D4), fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }

        // Reading zone hugging the top edge, right under the selfie lens.
        AutoScrollTeleprompter(
            text, style.copy(bgAlpha = 0.82f), playing, wpm, voice, speaking, matched,
            Modifier.fillMaxWidth().fillMaxHeight(0.44f).padding(horizontal = 12.dp, vertical = 54.dp).align(Alignment.TopCenter)
        )

        if (countdown > 0) {
            Text("$countdown", fontSize = 120.sp, color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            color = Color(0xE613151D), shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            shadowElevation = 12.dp
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Mic, null, tint = Color(0xFF9BA3B4), modifier = Modifier.size(17.dp))
                    Waveform(wave, Modifier.weight(1f).height(28.dp))
                    Text(if (voice) "VOICE FOLLOW ON" else "VOICE FOLLOW OFF", color = if (voice) Color(0xFF00E5D4) else Color(0xFF9BA3B4), fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                    IconButton({ front = !front }, enabled = !recording) {
                        Icon(Icons.Default.FlipCameraAndroid, "Flip camera", tint = Color.White)
                    }
                    FilledIconButton(
                        onClick = {
                            if (cameraEnabled) {
                                if (recording || countdown > 0) vm.stop() else vm.startWithCountdown()
                            } else {
                                TeleprompterSession.togglePlay()
                            }
                        },
                        modifier = Modifier.size(58.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (recording) Color(0xFFE5484D) else Color(0xFF00E5D4))
                    ) {
                        val pausedIcon = !cameraEnabled && playing
                        Icon(
                            when { recording -> Icons.Default.Stop; pausedIcon -> Icons.Default.Pause; else -> Icons.Default.PlayArrow },
                            if (recording) "Stop recording" else if (cameraEnabled) "Start recording" else if (playing) "Pause scrolling" else "Start scrolling",
                            tint = if (recording) Color.White else Color(0xFF00201D), modifier = Modifier.size(27.dp)
                        )
                    }
                    IconButton({ muted = !muted; vm.recorder.setMuted(muted) }) {
                        Icon(if (muted) Icons.Default.MicOff else Icons.Default.Mic, if (muted) "Unmute" else "Mute", tint = if (muted) Color(0xFFE5484D) else Color.White)
                    }
                    TextButton({ uhd = !uhd }, enabled = !recording) {
                        Text(if (uhd) "4K" else "1080p", color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
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
