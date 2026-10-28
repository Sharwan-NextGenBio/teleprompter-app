package com.example.teleprompter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.teleprompter.engine.TeleprompterSession
import kotlin.math.abs

/** Wrap words in ==double equals== to get a highlight marker. */
fun parseHighlights(src: String, bg: Color = Color(0xFFFFEB3B)): AnnotatedString = buildAnnotatedString {
    src.split("==").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(background = bg, color = Color.Black)) { append(part) }
        else append(part)
    }
}

fun wordCount(text: String): Int =
    text.replace("==", "").trim().split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)

@Composable
fun AutoScrollTeleprompter(
    text: String,
    style: TeleprompterSession.Style,
    isPlaying: Boolean,
    wpm: Int,
    voiceFollow: Boolean,
    isSpeaking: Boolean,
    matchedWord: Int,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {}
) {
    val scroll = rememberScrollState()
    val words = remember(text) { wordCount(text) }
    val annotated = remember(text) { parseHighlights(text) }
    var textHeightPx by remember { mutableIntStateOf(0) }

    // Read live inside the long-running scroll loop without restarting it.
    val gate by rememberUpdatedState(!voiceFollow || isSpeaking)
    val liveWpm by rememberUpdatedState(wpm)
    val finished by rememberUpdatedState(onFinished)

    LaunchedEffect(text) { scroll.scrollTo(0) }

    BoxWithConstraints(modifier.clipToBounds().background(Color.Black.copy(alpha = style.bgAlpha))) {
        val density = LocalDensity.current
        val viewportPx = constraints.maxHeight.toFloat()
        val eyeLinePx = viewportPx * style.eyeLine
        val leadDp = with(density) { eyeLinePx.toDp() }
        val trailDp = with(density) { (viewportPx - eyeLinePx).toDp() }

        // ---- WPM-based smooth scrolling (frame-synced, sub-pixel carry) ----
        LaunchedEffect(isPlaying, textHeightPx, words) {
            if (!isPlaying || textHeightPx == 0) return@LaunchedEffect
            var last = withFrameNanos { it }
            var carry = 0f
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f
                last = now
                if (!gate) continue                       // paused: reader is silent
                val pxPerSec = textHeightPx.toFloat() / words * liveWpm / 60f
                carry += pxPerSec * dt
                val step = carry.toInt()
                if (step > 0) { scroll.scrollBy(step.toFloat()); carry -= step }
                if (scroll.value >= scroll.maxValue) { finished(); break }
            }
        }

        // ---- Voice-follow: re-sync when the reader drifts from the scroll position ----
        LaunchedEffect(matchedWord, voiceFollow) {
            if (!voiceFollow || textHeightPx == 0) return@LaunchedEffect
            val target = (matchedWord / words.toFloat() * textHeightPx).toInt().coerceIn(0, scroll.maxValue)
            if (abs(target - scroll.value) > eyeLinePx * 0.25f) scroll.animateScrollTo(target)
        }

        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {                         // Mirror mode for teleprompter glass/rigs
                    scaleX = if (style.mirrorH) -1f else 1f
                    scaleY = if (style.mirrorV) -1f else 1f
                }
                .verticalScroll(scroll)
                .padding(horizontal = style.marginDp.dp)
        ) {
            Spacer(Modifier.height(leadDp))              // first line starts on the eye line
            Text(
                text = annotated,
                color = Color(style.textColor),
                fontSize = style.fontSizeSp.sp,
                lineHeight = (style.fontSizeSp * 1.4f).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                onTextLayout = { textHeightPx = it.size.height }
            )
            Spacer(Modifier.height(trailDp))
        }

        // Eye-line band: dim already-read text above, tint the active line.
        Canvas(Modifier.fillMaxSize()) {
            val lineH = style.fontSizeSp.sp.toPx() * 1.4f
            drawRect(
                Brush.verticalGradient(listOf(Color.Black.copy(0.85f), Color.Transparent), startY = 0f, endY = eyeLinePx),
                size = Size(size.width, eyeLinePx)
            )
            drawRect(
                Color(0xFF4FC3F7).copy(alpha = 0.14f),
                topLeft = Offset(0f, eyeLinePx),
                size = Size(size.width, lineH)
            )
        }
    }
}
