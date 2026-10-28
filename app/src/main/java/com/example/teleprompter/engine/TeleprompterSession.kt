package com.example.teleprompter.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Single source of truth shared by the Activity, the overlay Service and the recording screen. */
object TeleprompterSession {

    data class Style(
        val fontSizeSp: Float = 32f,
        val textColor: Long = 0xFFFFFFFF,
        val marginDp: Float = 16f,
        val bgAlpha: Float = 0.7f,
        val mirrorH: Boolean = false,
        val mirrorV: Boolean = false,
        /** Where the active reading line sits (0 = very top, near the selfie lens). */
        val eyeLine: Float = 0.12f
    )

    val text = MutableStateFlow("")
    val isPlaying = MutableStateFlow(false)
    val wpm = MutableStateFlow(140)
    val style = MutableStateFlow(Style())

    val voiceFollow = MutableStateFlow(false)
    val isSpeaking = MutableStateFlow(true)
    /** Index of the last script word the recognizer matched. */
    val matchedWord = MutableStateFlow(0)

    fun togglePlay() = isPlaying.update { !it }
    fun adjustWpm(delta: Int) = wpm.update { (it + delta).coerceIn(40, 400) }
}
