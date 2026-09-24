package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import com.ultrabytecoder.coinsafebox.security.GestureEntropyAccumulator
import kotlin.math.hypot
import kotlin.time.TimeSource

/** Minimum inter-sample distance (dp) below which samples are micro-jitter. */
private const val MIN_DISTANCE_DP = 4f

/** Cumulative drag distance (dp) required to reach 100% progress. */
private const val TARGET_DISTANCE_DP = 3000f

/** Fraction of progress a single tap contributes (100 taps complete it). */
private const val TAP_PROGRESS_INCREMENT = 0.01f

/** Max points kept in the visible comet trail. */
private const val MAX_TRAIL_POINTS = 30

private data class TrailPoint(val x: Float, val y: Float)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureEntropyScreen(
    onBack: () -> Unit,
    onConfirm: (digest: ByteArray) -> Unit
) {
    val accumulator = remember { GestureEntropyAccumulator() }
    var progress by remember { mutableStateOf(0f) }
    var isComplete by remember { mutableStateOf(false) }
    // Rolling list of recent points used to render the fading comet trail.
    var trail by remember { mutableStateOf<List<TrailPoint>>(emptyList()) }
    // Bumped on every pointer move so the Canvas recomposes and redraws the
    // trail immediately — a List is not observable state on its own.
    var drawTick by remember { mutableStateOf(0) }
    // Monotonic baseline for sample timestamps (cross-platform, unlike System.nanoTime).
    val gestureStart = remember { TimeSource.Monotonic.markNow() }

    val density = LocalDensity.current
    val minDistancePx = with(density) { MIN_DISTANCE_DP.dp.toPx() }
    val targetDistancePx = with(density) { TARGET_DISTANCE_DP.dp.toPx() }

    DisposableEffect(Unit) {
        onDispose { accumulator.wipe() }
    }

    fun appendTrail(x: Float, y: Float) {
        val current = trail
        val next = if (current.size >= MAX_TRAIL_POINTS) {
            current.drop(1) + TrailPoint(x, y)
        } else {
            current + TrailPoint(x, y)
        }
        trail = next
        drawTick++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extra entropy") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                "Draw a gesture",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Your drawing is folded into a one-time hash and mixed with the system " +
                    "random entropy. Nothing is stored — this is only needed once.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            val canvasStroke = MaterialTheme.colorScheme.primary
            val canvasHint = MaterialTheme.colorScheme.surfaceVariant
            val trailAlpha by animateFloatAsState(
                targetValue = if (trail.isEmpty()) 0f else 1f,
                animationSpec = tween(durationMillis = 300),
                label = "trailAlpha"
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                if (isComplete) return@detectTapGestures
                                accumulator.addSample(
                                    x = offset.x,
                                    y = offset.y,
                                    pressure = 1f,
                                    timestampMillis = gestureStart.elapsedNow().inWholeMilliseconds
                                )
                                appendTrail(offset.x, offset.y)
                                progress = (progress + TAP_PROGRESS_INCREMENT).coerceIn(0f, 1f)
                                isComplete = progress >= 1f
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    if (isComplete) return@detectDragGestures
                                    // A new gesture starts a fresh visual trail but keeps
                                    // accumulating entropy and progress (like the reference app).
                                    trail = emptyList()
                                    accumulator.addSample(
                                        x = offset.x,
                                        y = offset.y,
                                        pressure = 1f,
                                        timestampMillis = gestureStart.elapsedNow().inWholeMilliseconds
                                    )
                                    appendTrail(offset.x, offset.y)
                                },
                                onDrag = { change, dragAmount ->
                                    if (isComplete) return@detectDragGestures
                                    change.consume()
                                    val x = change.position.x
                                    val y = change.position.y
                                    val dist = hypot(dragAmount.x, dragAmount.y).toFloat()
                                    // Drop sub-threshold samples to filter micro-jitter / static holds.
                                    if (dist > minDistancePx) {
                                        accumulator.addSample(
                                            x = x,
                                            y = y,
                                            pressure = normalizedPressure(change),
                                            timestampMillis = change.uptimeMillis
                                        )
                                        appendTrail(x, y)
                                        progress = (progress + dist / targetDistancePx).coerceIn(0f, 1f)
                                        isComplete = progress >= 1f
                                    }
                                }
                            )
                        }
                ) {
                    drawTick
                    drawTrail(trail, isComplete, canvasStroke)
                }

                // Hint text overlay — fades out when the user starts drawing.
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(18.dp)
                        .alpha(trailAlpha),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Draw or tap",
                        color = canvasHint,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Swipe freely to generate additional random entropy",
                        color = canvasHint,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val percent = (progress * 100).toInt().coerceIn(0, 100)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Gesture: $percent%",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (isComplete) "Completed" else "Collecting...",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(13.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val digest = accumulator.finalize()
                    accumulator.wipe()
                    onConfirm(digest)
                },
                enabled = isComplete,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Continue")
            }
        }
    }
}

private fun normalizedPressure(change: PointerInputChange): Float {
    val p = change.pressure
    return if (p.isNaN() || p <= 0f) 1f else p.coerceAtMost(1f)
}

/**
 * Draw the comet-tail trail with a fading glow, per-segment alpha/width
 * gradient, a bright head dot, and a completion flash overlay.
 */
private fun DrawScope.drawTrail(
    points: List<TrailPoint>,
    isComplete: Boolean,
    color: Color
) {
    if (points.isEmpty()) return

    // 1) Outer glow — wide, low-alpha stroke for the halo.
    drawPath(
        path = buildSmoothPath(points),
        color = color.copy(alpha = 0.22f),
        style = Stroke(width = 18f, cap = StrokeCap.Round)
    )

    // 2) Per-segment comet fade.
    drawCometSegments(points, color)

    // 3) Bright head dot.
    val head = points.last()
    drawCircle(color = color, radius = 10f, center = Offset(head.x, head.y))
    drawCircle(
        color = Color.White.copy(alpha = 0.85f),
        radius = 4f,
        center = Offset(head.x, head.y)
    )

    // 4) Completion flash overlay.
    if (isComplete) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(head.x, head.y),
                radius = 400f
            ),
            center = Offset(head.x, head.y),
            radius = 400f
        )
    }
}

/** Build a smoothed Path via midpoint quadratic Béziers. */
private fun buildSmoothPath(points: List<TrailPoint>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path

    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val midX = (a.x + b.x) / 2f
        val midY = (a.y + b.y) / 2f
        if (i == 0) {
            path.lineTo(midX, midY)
        } else {
            path.quadraticTo(a.x, a.y, midX, midY)
        }
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}

/**
 * Draw each segment with alpha/width proportional to recency, producing the
 * comet-tail fade. Reuses a single [Path] to avoid per-segment GC pressure.
 */
private fun DrawScope.drawCometSegments(
    points: List<TrailPoint>,
    color: Color
) {
    if (points.size < 2) return
    val n = points.size
    val seg = Path()
    for (i in 0 until n - 1) {
        val a = points[i]
        val b = points[i + 1]
        val t = (i + 1).toFloat() / n // 0 (tail) → 1 (head)
        val alpha = 0.12f + 0.78f * t
        val width = 2f + 6f * t
        seg.reset()
        seg.moveTo(a.x, a.y)
        val midX = (a.x + b.x) / 2f
        val midY = (a.y + b.y) / 2f
        seg.quadraticTo(a.x, a.y, midX, midY)
        seg.lineTo(b.x, b.y)
        drawPath(
            seg,
            color = color.copy(alpha = alpha),
            style = Stroke(width = width, cap = StrokeCap.Round)
        )
    }
}
