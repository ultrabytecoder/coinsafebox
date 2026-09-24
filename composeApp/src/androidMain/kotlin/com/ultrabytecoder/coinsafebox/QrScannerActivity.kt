package com.ultrabytecoder.coinsafebox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.ReaderException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.InvertedLuminanceSource
import com.ultrabytecoder.coinsafebox.ui.util.applySecureFlag
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RESULT = "result"
        // ZXing decodes better on moderately-sized images; capping the decode
        // resolution keeps per-frame cost low so the analysis stream stays fast.
        const val DECODE_MAX_DIM = 900
    }

    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qr-analysis").apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                android.util.Log.e("QrScanner", "Analyzer thread exception", e)
            }
        }
    }
    private val resultDelivered = AtomicBoolean(false)
    private var frameCounter = 0

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
            @ExperimentalCamera2Interop { granted ->
                if (granted) {
                    startCamera()
                } else {
                    finish()
                }
            }
        )

    @ExperimentalCamera2Interop
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots / screen recordings / app-switcher previews of the
        // scanned content, consistent with the rest of the app. Skipped in debug
        // builds so scrcpy works during development / QA.
        window.applySecureFlag()

        setContent {
            ScannerUi(
                onClose = { finish() },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                onPreviewReady = @ExperimentalCamera2Interop { view ->
                    previewView = view
                    cameraProvider?.let { bindPreview(it) }
                }
            )
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @ExperimentalCamera2Interop
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            bindPreview(provider)
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    @ExperimentalCamera2Interop
    private fun bindPreview(provider: ProcessCameraProvider) {
        val targetRotation = windowManager.defaultDisplay.rotation

        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build().also {
                previewView?.let { view -> it.setSurfaceProvider(view.surfaceProvider) }
            }
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(targetRotation)
            .setTargetResolution(android.util.Size(1920, 1080))

        // Enable continuous autofocus — critical for close-range screen-QR scanning.
        try {
            Camera2Interop.Extender(analysisBuilder).apply {
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }
        } catch (t: Throwable) {
            android.util.Log.w("QrScanner", "Camera2Interop unavailable, AF not set", t)
        }

        imageAnalysis = analysisBuilder
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    analyzeFrame(proxy)
                }
            }
        provider.unbindAll()
        provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis
        )
    }

    /**
     * Decode one ImageAnalysis frame.
     *
     * Pipeline:
     *   1. Extract the Y plane (pixelStride must be 1).
     *   2. Bounds-check against rowStride*height.
     *   3. Downscale to at most [DECODE_MAX_DIM] px — ZXing decodes better on
     *      downscaled images, and full-res 6MP decode attempts would crawl.
     *   4. Rotate the downscaled Y plane to upright display orientation.
     *   5. Build a PlanarYUVLuminanceSource with crop == full rotated frame.
     *   6. Try 4 binarizer variants: Hybrid, GlobalHistogram, + each inverted.
     *   7. Return first successful decode.
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val plane = imageProxy.planes.firstOrNull()
            if (plane == null) return

            val buffer = plane.buffer
            buffer.rewind()
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // Y plane of YUV_420_888 must have pixelStride == 1.
            if (pixelStride != 1) {
                if (frameCounter % 300 == 0) {
                    android.util.Log.w("QrScanner", "pixelStride=$pixelStride != 1, skipping frame")
                }
                return
            }

            // Ensure we have at least enough to cover rowStride*(height-1) + width samples.
            val needed = rowStride * (height - 1) + width
            if (width <= 0 || height <= 0 || data.size < needed) {
                if (frameCounter % 300 == 0) {
                    android.util.Log.w(
                        "QrScanner",
                        "Bad frame dims: ${width}x$height rowStride=$rowStride data=${data.size}B needed=$needed"
                    )
                }
                return
            }

            frameCounter++
            if (frameCounter <= 1 || frameCounter % 300 == 0) {
                android.util.Log.d(
                    "QrScanner",
                    "Frame #$frameCounter: ${width}x$height rowStride=$rowStride pixelStride=$pixelStride " +
                        "rot=${imageProxy.imageInfo.rotationDegrees}deg data=${data.size}B needed=$needed"
                )
            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // Downscale first so rotation + all decode attempts stay fast.
            val (smallData, smallW, smallH) = downscaleYPlane(data, rowStride, width, height)
            val (rotatedData, outW, outH) = rotateYPlane(
                smallData, smallW, smallW, smallH, rotationDegrees
            )

            // Diagnostic PGM dumps: frame #1 (raw + rotated), then every 60th frame
            // up to 20 total, so we capture a frame that actually contains the QR.
            if (frameCounter == 1) {
                dumpPgm(data, width, height, rotationDegrees, raw = true)
            }
            if (frameCounter == 1 || (frameCounter % 60 == 0 && frameCounter <= 1200)) {
                dumpPgm(rotatedData, outW, outH, rotationDegrees, raw = false)
            }

            // After rotation the buffer is compact (no rowStride padding).
            val source = PlanarYUVLuminanceSource(
                rotatedData,
                outW,      // dataWidth (rowStride of the rotated, unpadded buffer)
                outH,      // dataHeight
                0, 0,      // crop origin
                outW, outH,// crop size == full frame
                false      // back camera: no horizontal mirror
            )

            // Order matters: Hybrid works best on most camera frames,
            // GlobalHistogram rescues screen-displayed / high-contrast QRs.
            val attempts: List<BinaryBitmap> = listOf(
                BinaryBitmap(HybridBinarizer(source)),
                BinaryBitmap(GlobalHistogramBinarizer(source)),
                BinaryBitmap(HybridBinarizer(InvertedLuminanceSource(source))),
                BinaryBitmap(GlobalHistogramBinarizer(InvertedLuminanceSource(source)))
            )

            for (bitmap in attempts) {
                try {
                    reader.reset()
                    val result = reader.decodeWithState(bitmap)
                    val raw = result.text
                    if (!raw.isNullOrBlank() && resultDelivered.compareAndSet(false, true)) {
                        android.util.Log.d("QrScanner", "Decoded on frame #$frameCounter")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, raw))
                                finish()
                            }
                        }
                    }
                    return
                } catch (_: ReaderException) {
                    // Try next binarizer variant.
                }
            }
            if (frameCounter % 300 == 0) {
                android.util.Log.d("QrScanner", "No QR found in frame #$frameCounter (all 4 attempts failed)")
            }
        } catch (t: Throwable) {
            // Never let an unexpected exception silently kill the analyzer thread —
            // CameraX will stop delivering frames if the analyzer throws repeatedly.
            android.util.Log.e("QrScanner", "analyzeFrame failed", t)
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }

    /**
     * Write a raw grayscale frame to filesDir/qr_debug/ as an ASCII-header PGM
     * (P5) file so we can inspect exactly what ZXing receives.
     */
    private fun dumpPgm(data: ByteArray, width: Int, height: Int, rotationDegrees: Int, raw: Boolean) {
        try {
            // External files dir is readable via `adb pull` even on release builds.
            val baseDir = getExternalFilesDir(null) ?: filesDir
            val dir = java.io.File(baseDir, "qr_debug").apply { mkdirs() }
            val tag = if (raw) "raw" else "rot"
            val file = java.io.File(dir, "frame_${tag}_${rotationDegrees}deg_${width}x${height}_n${frameCounter}.pgm")
            file.outputStream().use { os ->
                os.write("P5\n$width $height\n255\n".toByteArray())
                os.write(data, 0, width * height)
            }
            android.util.Log.d("QrScanner", "Dumped frame to ${file.absolutePath}")
        } catch (t: Throwable) {
            android.util.Log.w("QrScanner", "PGM dump failed", t)
        }
    }

    /**
     * Box-downsample the Y plane so the longest edge is at most DECODE_MAX_DIM.
     * Also compacts away rowStride padding. Returns (data, newWidth, newHeight).
     */
    private fun downscaleYPlane(
        data: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int
    ): Triple<ByteArray, Int, Int> {
        val scale = kotlin.math.max(1, kotlin.math.ceil(kotlin.math.max(width, height).toDouble() / DECODE_MAX_DIM).toInt())
        if (scale == 1 && rowStride == width) {
            return Triple(data, width, height)
        }
        val outW = width / scale
        val outH = height / scale
        val out = ByteArray(outW * outH)
        for (y in 0 until outH) {
            val srcY0 = y * scale
            val dstBase = y * outW
            for (x in 0 until outW) {
                var sum = 0
                for (dy in 0 until scale) {
                    val srcY = srcY0 + dy
                    if (srcY >= height) break
                    val rowBase = srcY * rowStride + x * scale
                    for (dx in 0 until scale) {
                        val srcX = x * scale + dx
                        if (srcX >= width) break
                        sum += data[rowBase + dx].toInt() and 0xFF
                    }
                }
                out[dstBase + x] = (sum / (scale * scale)).toByte()
            }
        }
        return Triple(out, outW, outH)
    }

    /**
     * Rotate a planar Y buffer clockwise by [rotation] degrees.
     *
     * Input layout:  rows of length [rowStride] (only first [width] bytes of each row
     * are valid Y samples), [height] rows total.
     *
     * Output layout: rows of length equal to the new width (no padding), in row-major
     * order. Returns (data, newWidth, newHeight).
     */
    private fun rotateYPlane(
        data: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int,
        rotation: Int
    ): Triple<ByteArray, Int, Int> {
        when (rotation) {
            0 -> {
                // No rotation — but still compact away rowStride padding.
                if (rowStride == width) {
                    return Triple(data, width, height)
                }
                val out = ByteArray(width * height)
                for (r in 0 until height) {
                    System.arraycopy(data, r * rowStride, out, r * width, width)
                }
                return Triple(out, width, height)
            }
            90 -> {
                val out = ByteArray(width * height)
                for (r in 0 until height) {
                    val srcRow = r * rowStride
                    val dstCol = height - 1 - r
                    for (c in 0 until width) {
                        out[c * height + dstCol] = data[srcRow + c]
                    }
                }
                return Triple(out, height, width)
            }
            180 -> {
                val out = ByteArray(width * height)
                for (r in 0 until height) {
                    val srcRow = r * rowStride
                    val dstRow = (height - 1 - r) * width
                    for (c in 0 until width) {
                        out[dstRow + (width - 1 - c)] = data[srcRow + c]
                    }
                }
                return Triple(out, width, height)
            }
            270 -> {
                val out = ByteArray(width * height)
                for (r in 0 until height) {
                    val srcRow = r * rowStride
                    for (c in 0 until width) {
                        out[(width - 1 - c) * height + r] = data[srcRow + c]
                    }
                }
                return Triple(out, height, width)
            }
            else -> return Triple(data, width, height)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        analysisExecutor.shutdown()
    }
}

@Composable
private fun ScannerUi(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onPreviewReady: (PreviewView) -> Unit
) {
    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    view.scaleType = PreviewView.ScaleType.FILL_CENTER
                    onPreviewReady(view)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Canvas(Modifier.fillMaxSize()) {
            val windowSize = size.minDimension * 0.72f
            val window = Rect(
                left = (size.width - windowSize) / 2f,
                top = (size.height - windowSize) / 2f,
                right = (size.width + windowSize) / 2f,
                bottom = (size.height + windowSize) / 2f
            )
            val scrim = Color.Black.copy(alpha = 0.5f)
            drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, window.top))
            drawRect(scrim, topLeft = Offset(0f, window.bottom), size = Size(size.width, size.height - window.bottom))
            drawRect(scrim, topLeft = Offset(0f, window.top), size = Size(window.left, windowSize))
            drawRect(scrim, topLeft = Offset(window.right, window.top), size = Size(size.width - window.right, windowSize))

            val corner = 28.dp.toPx()
            val strokeWidth = 5.dp.toPx()
            val accent = Color(0xFF4FC3F7)
            val pts = listOf(
                Offset(window.left, window.top + corner) to Offset(window.left, window.top),
                Offset(window.left, window.top) to Offset(window.left + corner, window.top),
                Offset(window.right - corner, window.top) to Offset(window.right, window.top),
                Offset(window.right, window.top) to Offset(window.right, window.top + corner),
                Offset(window.left, window.bottom - corner) to Offset(window.left, window.bottom),
                Offset(window.left, window.bottom) to Offset(window.left + corner, window.bottom),
                Offset(window.right - corner, window.bottom) to Offset(window.right, window.bottom),
                Offset(window.right, window.bottom) to Offset(window.right, window.bottom - corner)
            )
            pts.forEach { (start, end) ->
                drawLine(accent, start, end, strokeWidth = strokeWidth)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.TopStart
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0x66000000), CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "\u2715",
                    color = Color.White,
                    fontSize = 20.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 48.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                text = "Align the QR code within the frame",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}