package com.bitchat.android.features.dogecoin.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The wallet's single ornament (the "Coin" direction): one gold ring around the balance, carrying every
 * meaning the wallet has — and never two indicators at once.
 *
 *  - [RingMode.IDLE]      a full solid gold circle = "all good" (synced, nothing pending).
 *  - [RingMode.SYNCING]   a continuous gold arc filling `progress` over a gray track (header sync).
 *  - [RingMode.CONFIRMING] [segments] discrete gold segments; `round(progress * segments)` are gold as
 *                          confirmations land (snaps one segment per confirmation, no tween).
 *
 * The ring is purely visual: the human-readable state lives in [centerContent], and the whole element is
 * replaced by [contentDescription] for screen readers (a ring is not screen-readable), so it degrades to
 * plain text (e.g. "2 of 6 confirmations").
 */
enum class RingMode { IDLE, SYNCING, CONFIRMING }

@Composable
fun ConfirmationRing(
    mode: RingMode,
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 220.dp,
    strokeWidth: Dp = 6.dp,
    segments: Int = 6,
    contentDescription: String? = null,
    centerContent: @Composable () -> Unit = {},
) {
    val track = dogeWalletColors.line
    val gold = dogeWalletColors.gold
    // Continuous arcs (idle/syncing) animate smoothly; discrete confirmation segments snap, so the count is
    // taken from the raw progress, not the tween.
    val sweep by animateFloatAsState(
        targetValue = (if (mode == RingMode.IDLE) 1f else progress).coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "ring-sweep"
    )

    Box(
        modifier = modifier
            .size(diameter)
            .then(
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            when (mode) {
                RingMode.CONFIRMING -> {
                    val n = segments.coerceAtLeast(1)
                    val filled = (progress.coerceIn(0f, 1f) * n).roundToInt()
                    val gapDeg = 6f
                    val segDeg = (360f - gapDeg * n) / n
                    val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Butt)
                    var start = -90f
                    repeat(n) { i ->
                        drawArc(
                            color = if (i < filled) gold else track,
                            startAngle = start,
                            sweepAngle = segDeg,
                            useCenter = false,
                            style = stroke
                        )
                        start += segDeg + gapDeg
                    }
                }
                else -> {
                    val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                    drawArc(color = track, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
                    if (sweep > 0f) {
                        drawArc(
                            color = gold,
                            startAngle = -90f,
                            sweepAngle = sweep * 360f,
                            useCenter = false,
                            style = stroke
                        )
                    }
                }
            }
        }
        // Center text/number sits inside the ring with a little breathing room from the stroke.
        Box(Modifier.padding(strokeWidth + 18.dp), contentAlignment = Alignment.Center) {
            centerContent()
        }
    }
}
