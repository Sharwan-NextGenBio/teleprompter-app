package com.example.teleprompter.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.teleprompter.engine.TeleprompterSession
import com.example.teleprompter.engine.VoiceFollowController
import com.example.teleprompter.ui.AutoScrollTeleprompter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Foreground service that owns a floating Compose window (TYPE_APPLICATION_OVERLAY).
 * Needs "Display over other apps" granted, and must be started while the app is visible (Android 14+).
 */
class OverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private val ssc = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val session = TeleprompterSession
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var root: ComposeView? = null
    private var mediaSession: MediaSessionCompat? = null
    private var voice: VoiceFollowController? = null

    override fun onCreate() {
        ssc.performAttach()          // must run before super.onCreate()
        ssc.performRestore(null)
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        showOverlay()
        setupMediaSession()
        setupVoiceFollow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Teleprompter overlay", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Teleprompter is running")
            .addAction(0, "Stop", stop).setOngoing(true).build()

        val type = if (Build.VERSION.SDK_INT >= 34)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (hasMic()) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        ServiceCompat.startForeground(this, 1, n, type)
    }

    private fun showOverlay() {
        val dm = resources.displayMetrics
        lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.9f).toInt(), (260 * dm.density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.05f).toInt()
            y = (48 * dm.density).toInt()
        }
        val min = (180 * dm.density).toInt()

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    OverlayTeleprompter(
                        onDrag = { dx, dy -> lp.x += dx.toInt(); lp.y += dy.toInt(); wm.updateViewLayout(this, lp) },
                        onResize = { dx, dy ->
                            lp.width = (lp.width + dx.toInt()).coerceAtLeast(min)
                            lp.height = (lp.height + dy.toInt()).coerceAtLeast(min)
                            wm.updateViewLayout(this, lp)
                        },
                        onClose = { stopSelf() }
                    )
                }
            }
        }
        wm.addView(view, lp)
        root = view
    }

    /** Lets Bluetooth clickers / media keys control the prompter even while another app is in front. */
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "TeleprompterSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = session.togglePlay()
                override fun onPause() = session.togglePlay()
                override fun onSkipToNext() = session.adjustWpm(+10)
                override fun onSkipToPrevious() = session.adjustWpm(-10)
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f).build()
            )
            isActive = true
        }
    }

    private fun setupVoiceFollow() {
        val c = VoiceFollowController(this, lifecycleScope).also { voice = it }
        lifecycleScope.launch { session.text.collect { c.setScript(it) } }
        lifecycleScope.launch {
            session.voiceFollow.collect { on -> if (on && hasMic()) c.start() else c.stop() }
        }
    }

    override fun onDestroy() {
        voice?.stop()
        mediaSession?.release()
        root?.let { runCatching { wm.removeView(it) } }
        viewModelStore.clear()
        super.onDestroy()
    }

    companion object {
        private const val CH = "overlay"
        const val ACTION_STOP = "com.example.teleprompter.STOP"
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
    }
}

/** Drag using raw screen coordinates, so it doesn't stutter while the window itself is moving. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.rawDrag(onDelta: (Float, Float) -> Unit): Modifier = composed {
    var lx by remember { mutableFloatStateOf(0f) }
    var ly by remember { mutableFloatStateOf(0f) }
    pointerInteropFilter { e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lx = e.rawX; ly = e.rawY }
            MotionEvent.ACTION_MOVE -> { onDelta(e.rawX - lx, e.rawY - ly); lx = e.rawX; ly = e.rawY }
        }
        true
    }
}

@Composable
private fun OverlayTeleprompter(
    onDrag: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    onClose: () -> Unit
) {
    val s = TeleprompterSession
    val text by s.text.collectAsState()
    val style by s.style.collectAsState()
    val playing by s.isPlaying.collectAsState()
    val wpm by s.wpm.collectAsState()
    val voice by s.voiceFollow.collectAsState()
    val speaking by s.isSpeaking.collectAsState()
    val matched by s.matchedWord.collectAsState()

    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) {
        AutoScrollTeleprompter(
            text, style, playing, wpm, voice, speaking, matched,
            Modifier.fillMaxSize(), onFinished = { s.isPlaying.value = false }
        )
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(0.65f)).padding(start = 4.dp, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.DragHandle, "Move", tint = Color.White, modifier = Modifier.size(36.dp).rawDrag(onDrag))
            IconButton({ s.togglePlay() }, Modifier.size(36.dp)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Color.White)
            }
            IconButton({ s.adjustWpm(-10) }, Modifier.size(32.dp)) { Icon(Icons.Default.Remove, "Slower", tint = Color.White) }
            Text("$wpm", color = Color.White)
            IconButton({ s.adjustWpm(+10) }, Modifier.size(32.dp)) { Icon(Icons.Default.Add, "Faster", tint = Color.White) }
            Slider(
                value = style.bgAlpha,
                onValueChange = { a -> s.style.update { it.copy(bgAlpha = a) } },
                valueRange = 0.1f..1f, modifier = Modifier.weight(1f)
            )
            IconButton(onClose, Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
        }
        Icon(
            Icons.Default.OpenInFull, "Resize", tint = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).size(32.dp).padding(6.dp).rawDrag(onResize)
        )
    }
}
