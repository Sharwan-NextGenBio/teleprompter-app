package com.example.teleprompter.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Listens continuously (on-device when available), reports:
 *  - isSpeaking  -> pauses/resumes the scroll
 *  - matchedWord -> lets the scroller re-sync to where the reader actually is
 * Must be created and used on the main thread.
 */
class VoiceFollowController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val locale: Locale = Locale.getDefault()
) {
    private val session = TeleprompterSession
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var ticker: Job? = null
    private var lastVoiceAt = 0L
    private var words: List<String> = emptyList()
    private var cursor = 0

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    fun setScript(text: String) {
        words = tokenize(text)
        cursor = 0
        session.matchedWord.value = 0
    }

    fun start() {
        if (running) return
        running = true
        recognizer = if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(listener)
        listen()
        ticker = scope.launch {
            while (isActive) {
                delay(200)
                if (System.currentTimeMillis() - lastVoiceAt > SILENCE_MS) session.isSpeaking.value = false
            }
        }
    }

    fun stop() {
        running = false
        ticker?.cancel()
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        session.isSpeaking.value = true   // no voice gating when the feature is off
    }

    private fun listen() = recognizer?.startListening(intent)
    private fun relisten() { main.postDelayed({ if (running) listen() }, 250) }

    private val listener = object : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) {
            if (rmsdB > VOICE_RMS) { lastVoiceAt = System.currentTimeMillis(); session.isSpeaking.value = true }
        }
        override fun onPartialResults(b: Bundle?) = match(b)
        override fun onResults(b: Bundle?) { match(b); relisten() }
        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) stop() else relisten()
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    /** Walk the spoken words forward through a small window of the script. */
    private fun match(b: Bundle?) {
        val heard = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        for (w in tokenize(heard)) {
            val end = minOf(words.size, cursor + WINDOW)
            for (i in cursor until end) if (words[i] == w) { cursor = i + 1; break }
        }
        session.matchedWord.value = cursor
    }

    private fun tokenize(s: String) =
        s.lowercase(locale).split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotBlank() }

    private companion object {
        const val SILENCE_MS = 800L
        const val VOICE_RMS = 2f
        const val WINDOW = 8
    }
}
