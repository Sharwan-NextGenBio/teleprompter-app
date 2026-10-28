package com.example.teleprompter.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface RecState {
    data object Idle : RecState
    data object Recording : RecState
    data class Finished(val uri: Uri) : RecState
    data class Error(val code: Int) : RecState
}

class CameraRecorderManager(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var muted = false

    private val _state = MutableStateFlow<RecState>(RecState.Idle)
    val state: StateFlow<RecState> = _state.asStateFlow()

    /** Last ~60 audio amplitude samples (0..1) for the waveform view. */
    private val _wave = MutableStateFlow<List<Float>>(emptyList())
    val wave: StateFlow<List<Float>> = _wave.asStateFlow()

    /** Bind preview + recorder. Call again to switch camera or quality. */
    suspend fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        front: Boolean,
        quality: Quality = Quality.FHD
    ) {
        val p = ProcessCameraProvider.getInstance(context).await()
        provider = p
        val selector = QualitySelector.fromOrderedList(
            listOf(quality, Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        val recorder = Recorder.Builder().setQualitySelector(selector).build()
        val capture = VideoCapture.Builder(recorder)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)   // saved video matches the selfie preview
            .build()
        videoCapture = capture
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        p.unbindAll()
        p.bindToLifecycle(
            owner,
            if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
            preview, capture
        )
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the UI before recording
    fun start() {
        val capture = videoCapture ?: return
        if (recording != null) return
        val name = "TP_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Teleprompter") // shows up in Gallery
        }
        val output = MediaStoreOutputOptions.Builder(
            context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        recording = capture.output.prepareRecording(context, output)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { e ->
                when (e) {
                    is VideoRecordEvent.Start -> { _state.value = RecState.Recording; _wave.value = emptyList() }
                    is VideoRecordEvent.Status -> {
                        // Verify scaling on your CameraX version: normalised if <= 1, raw PCM peak otherwise.
                        val raw = e.recordingStats.audioStats.audioAmplitude.toFloat()
                        val a = (if (raw > 1f) raw / 32767f else raw).coerceIn(0f, 1f)
                        _wave.update { (it + a).takeLast(60) }
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        _state.value =
                            if (e.hasError()) RecState.Error(e.error) else RecState.Finished(e.outputResults.outputUri)
                        _wave.value = emptyList()
                    }
                    else -> Unit
                }
            }
        recording?.mute(muted)
    }

    fun stop() { recording?.stop() }
    fun setMuted(m: Boolean) { muted = m; recording?.mute(m) }
    fun release() { recording?.stop(); recording = null; provider?.unbindAll() }
}
