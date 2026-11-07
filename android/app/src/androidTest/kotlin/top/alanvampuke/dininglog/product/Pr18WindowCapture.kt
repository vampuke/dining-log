package top.alanvampuke.dininglog.product

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.test.platform.graphics.HardwareRendererCompat
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import org.junit.Assert.*

internal data class Pr18CaptureAttempt(val file: File, val source: Pr18CaptureSource)
internal data class Pr18WindowCaptureResult(val capture: Pr18CaptureRecord? = null, val failure: Throwable? = null)

// Row-sized storage bounds allocation while checking every real Activity pixel.
// Normal component frames can paint secondary/accent text without primary ink.
// Callers retain their exact state/color gates; background alone is not content.
internal fun pr18ValidateActivityBitmap(bitmap: Bitmap, width: Int, height: Int,
    background: Int, surface: Int, ink: Int, foregrounds: Set<Int>, record: (String) -> Unit): Pr18PixelFacts {
    assertTrue("normal capture has positive actual geometry", width > 0 && height > 0)
    assertEquals("normal capture geometry width", width, bitmap.width)
    assertEquals("normal capture geometry height", height, bitmap.height)
    val pixels = pr18ReadPixels(width, height, background, surface, ink, foregrounds) { y, row ->
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
    }
    record("activity-pixels=$pixels expectedBackground=$background expectedSurface=$surface expectedForegrounds=${foregrounds.sorted()}")
    pr18ValidateProductPixels(pixels, Pr18CaptureMethod.ACTIVITY_IMAGE)
    return pixels
}

internal fun pr18DecodePng(bytes: ByteArray): Pr18DecodedPixels? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return try {
        pr18ReadPixels(bitmap.width, bitmap.height, 0, 0, 0, emptySet()) { y, row ->
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }.decodedPixels()
    } finally { bitmap.recycle() }
}

// Both capture adapters enter this storage boundary. The expectation is frozen
// from the captured bitmap and encoder buffer before either artifact is written.
internal fun pr18PersistCapturedPng(attempt: Pr18CaptureAttempt, provenance: Pr18CaptureProvenance,
    bitmap: Bitmap, pixels: Pr18PixelFacts, record: (String) -> Unit): Pr18CaptureRecord {
    pr18RequireRuntimeCapture(provenance)
    val buffer = ByteArrayOutputStream()
    val compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
    val bytes = buffer.toByteArray()
    val seal = pr18SealPng(attempt.source, provenance, pixels.decodedPixels(), bytes, compressed, ::pr18DecodePng)
    val suffix = when (provenance.status) {
        Pr18CaptureStatus.NORMAL_PNG -> ""
        Pr18CaptureStatus.SECURE_PNG -> "-secure-readable"
        Pr18CaptureStatus.SECURE_REDACTED -> "-secure-redacted"
        Pr18CaptureStatus.SECURE_DENIED -> error("Denied capture has no PNG")
    }
    val file = File(attempt.file.parentFile, "${attempt.file.nameWithoutExtension}$suffix.png")
    val metadata = File(file.parentFile, "${file.nameWithoutExtension}.capture.txt")
    check(!file.exists() && !metadata.exists()) { "Capture artifact already exists" }
    file.writeBytes(bytes)
    metadata.writeText(seal.metadata)
    val capture = Pr18CaptureRecord(provenance, seal.encodedSha256) {
        seal.requireReadback(file.readBytes(), metadata.readText(), provenance, ::pr18DecodePng)
    }
    capture.requireValid()
    record("captureStatus=${provenance.status} readable=${provenance.status != Pr18CaptureStatus.SECURE_REDACTED} " +
        "png=${file.absolutePath} metadata=${metadata.absolutePath} sha256=${seal.encodedSha256} " +
        "dimensions=${bitmap.width}x${bitmap.height} provenance=$provenance")
    return capture
}

private fun pr18PersistSecureDenial(attempt: Pr18CaptureAttempt, provenance: Pr18CaptureProvenance,
    record: (String) -> Unit): Pr18CaptureRecord {
    pr18RequireCaptureSource(attempt.source, provenance)
    pr18RequireRuntimeCapture(provenance)
    check(provenance.status == Pr18CaptureStatus.SECURE_DENIED && provenance.source.secureBefore && provenance.secureAfter &&
        provenance.source.method == Pr18CaptureMethod.WINDOW_PIXEL_COPY && provenance.platformResult == PixelCopy.ERROR_SOURCE_INVALID) {
        "Secure denial requires the observed protected own-window result"
    }
    val commitment = "$provenance\npng=none\nreadable=false"
    val expected = "pr18-secure-denial-v1\n$commitment\nbindingSha256=${pr18Sha256(commitment.toByteArray(Charsets.UTF_8))}\n"
    val metadata = File(attempt.file.parentFile, "${attempt.file.nameWithoutExtension}.capture.txt")
    check(!attempt.file.exists() && !metadata.exists()) { "Denied capture must not claim a PNG" }
    metadata.writeText(expected)
    val capture = Pr18CaptureRecord(provenance, null) {
        check(!attempt.file.exists() && metadata.readText() == expected) { "Secure-denial receipt changed or acquired a PNG" }
    }
    capture.requireValid()
    record("captureStatus=SECURE_DENIED png=none readable=false metadata=${metadata.absolutePath} provenance=$provenance")
    return capture
}

internal fun pr18OwnWindowCapture(
    evidence: Pr18Evidence, node: SemanticsNode, window: Window, attempt: Pr18CaptureAttempt,
    protected: Boolean, background: Int, surface: Int, ink: Int, foregrounds: Set<Int>,
): Pr18WindowCaptureResult {
    val file = attempt.file
    val compose = evidence.compose
    val view = (node.root as ViewRootForTest).view
    val decor = window.decorView
    val handler = Handler(Looper.getMainLooper())
    val flagsBefore = compose.runOnIdle { window.attributes.flags }
    var callback: Int? = null
    var drawn = false
    var committed = false
    var outcome = "capture-failed"
    var copiedBitmap: Bitmap? = null
    val receipt = File(file.parentFile, "${file.nameWithoutExtension}.txt")
    val drawingEnabledBefore = HardwareRendererCompat.isDrawingEnabled()
    fun record(value: String) {
        val line = "own-window file=${file.absolutePath} $value"
        receipt.appendText("$line\n")
        evidence.record(line)
    }
    try {
        require(Build.VERSION.SDK_INT >= 26) { "Own-window PixelCopy requires API26+" }
        record("hardwareDrawingEnabledBefore=$drawingEnabledBefore")
        // Same hardware-window preparation as pinned Compose WindowCapture;
        // PixelCopy remains the only capture operation, with secure flags intact.
        if (!drawingEnabledBefore) HardwareRendererCompat.setDrawingEnabled(true)
        assertEquals("capture retains its original source", attempt.source,
            evidence.observeCaptureSource(attempt.source, node, window))
        val crop = compose.runOnIdle {
            assertEquals("owner flags stable before capture", flagsBefore, window.attributes.flags)
            check(view.rootView === decor.rootView && view.isAttachedToWindow) { "Capture root/owner mismatch" }
            assertEquals("capture secure state before", protected, flagsBefore and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val offset = IntArray(2).also { view.getLocationInWindow(it) }
            val b = node.boundsInRoot
            val windowCrop = Rect(b.left.roundToInt() + offset[0], b.top.roundToInt() + offset[1],
                b.right.roundToInt() + offset[0], b.bottom.roundToInt() + offset[1])
            check(!windowCrop.isEmpty && windowCrop.left >= 0 && windowCrop.top >= 0 &&
                windowCrop.right <= decor.width && windowCrop.bottom <= decor.height) { "Capture crop outside actual decor" }
            record("owner=${System.identityHashCode(window)} root=${System.identityHashCode(view)} " +
                "host=${System.identityHashCode(compose.activity.window)} token=${System.identityHashCode(view.windowToken)} " +
                "flagsBefore=$flagsBefore protected=$protected windowOffset=${offset.toList()} " +
                "full=${pr18FullBounds(node)} clipped=$b windowFullPosition=${node.positionInWindow} " +
                "windowClipped=${node.boundsInWindow} requestedWindowCrop=$windowCrop decor=${decor.width}x${decor.height} " +
                "hardware=${decor.isHardwareAccelerated} attached=${decor.isAttachedToWindow} focused=${decor.hasWindowFocus()}")
            windowCrop
        }
        val frame = CountDownLatch(1)
        val drawObserved = AtomicBoolean(false)
        val commitObserved = AtomicBoolean(false)
        lateinit var listener: ViewTreeObserver.OnDrawListener
        listener = ViewTreeObserver.OnDrawListener {
            if (drawObserved.compareAndSet(false, true)) handler.post {
                decor.viewTreeObserver.removeOnDrawListener(listener)
                if (Build.VERSION.SDK_INT < 29 || !decor.isHardwareAccelerated) frame.countDown()
            }
        }
        compose.runOnIdle {
            decor.viewTreeObserver.addOnDrawListener(listener)
            if (Build.VERSION.SDK_INT >= 29 && decor.isHardwareAccelerated) {
                decor.viewTreeObserver.registerFrameCommitCallback {
                    commitObserved.set(true)
                    frame.countDown()
                }
            }
            decor.invalidate()
        }
        val ready = frame.await(2, TimeUnit.SECONDS)
        drawn = drawObserved.get(); committed = commitObserved.get()
        compose.runOnIdle { decor.viewTreeObserver.removeOnDrawListener(listener) }
        record("frameReady=$ready drawObserved=$drawn frameCommitted=$committed")
        check(ready && drawn) { "Own-window frame not ready" }
        compose.runOnIdle {
            val offset = IntArray(2).also { view.getLocationInWindow(it) }
            val b = node.boundsInRoot
            val settledCrop = Rect(b.left.roundToInt() + offset[0], b.top.roundToInt() + offset[1],
                b.right.roundToInt() + offset[0], b.bottom.roundToInt() + offset[1])
            record("settledWindowCrop=$settledCrop ownerAttached=${view.isAttachedToWindow}")
            check(view.isAttachedToWindow && crop == settledCrop) { "Capture geometry changed before PixelCopy request" }
        }
        // API26/27 Window.request forwards raw Surface coordinates. API28+ offsets
        // surfaceInsets itself. Read the ACTUAL API26 ViewRootImpl attributes;
        // this is a read-only probe of pinned android-8.0.0_r1 fields, no estimate.
        val requestCrop = Rect(crop)
        if (Build.VERSION.SDK_INT < 28) compose.runOnIdle {
            val root = requireNotNull(decor.parent) { "API26 backing ViewRootImpl missing" }
            check(root.javaClass.name == "android.view.ViewRootImpl") { "API26 backing root unknown" }
            val attributes = root.javaClass.getDeclaredField("mWindowAttributes").apply { isAccessible = true }.get(root)
            val insets = WindowManager.LayoutParams::class.java.getField("surfaceInsets").get(attributes) as Rect
            requestCrop.offset(insets.left, insets.top)
            record("surfaceInsetsSource=ViewRootImpl.mWindowAttributes.surfaceInsets observed=$insets")
        }
        val bitmap = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        copiedBitmap = bitmap
        val complete = CountDownLatch(1)
        val submittedCrop = Rect(requestCrop)
        record("requestedPixelCopyCrop=$requestCrop bitmap=${bitmap.width}x${bitmap.height} attempt=1")
        PixelCopy.request(window, requestCrop, bitmap, { result -> callback = result; complete.countDown() }, handler)
        check(complete.await(1, TimeUnit.SECONDS)) { "Own-window PixelCopy callback timeout" }
        record("callbackResult=$callback submittedCropAfterPlatformOffsets=$requestCrop")
        val copyResult = when (callback) {
            PixelCopy.SUCCESS -> Pr18CopyResult.SUCCESS
            PixelCopy.ERROR_SOURCE_INVALID -> Pr18CopyResult.SOURCE_INVALID
            PixelCopy.ERROR_UNKNOWN -> Pr18CopyResult.UNKNOWN
            PixelCopy.ERROR_TIMEOUT -> Pr18CopyResult.TIMEOUT
            PixelCopy.ERROR_SOURCE_NO_DATA -> Pr18CopyResult.SOURCE_NO_DATA
            PixelCopy.ERROR_DESTINATION_INVALID -> Pr18CopyResult.DESTINATION_INVALID
            else -> null
        }
        val secureAfter = compose.runOnIdle {
            check(view.isAttachedToWindow && decor.isAttachedToWindow && view.rootView === decor.rootView) {
                "Capture owner disappeared during PixelCopy"
            }
            window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        }
        assertEquals("capture source/provenance remains immutable", attempt.source,
            evidence.observeCaptureSource(attempt.source, node, window))
        val flagsAfterCopy = compose.runOnIdle { window.attributes.flags }
        fun provenance(status: Pr18CaptureStatus) = Pr18CaptureProvenance(attempt.source, status,
            flagsAfterCopy, secureAfter, callback, "windowCrop=$crop submittedSurfaceCrop=$submittedCrop platformCrop=$requestCrop " +
                "frameReady=$ready drawObserved=$drawn frameCommitted=$committed")
        if (callback != PixelCopy.SUCCESS) {
            outcome = if (protected && callback == PixelCopy.ERROR_SOURCE_INVALID) "expected-secure-denied" else "capture-failed"
            val status = pr18CaptureStatus(protected, flagsBefore and WindowManager.LayoutParams.FLAG_SECURE != 0, secureAfter, copyResult)
            outcome = status.name.lowercase()
            return Pr18WindowCaptureResult(capture = pr18PersistSecureDenial(attempt, provenance(status), ::record))
        }
        val pixels = pr18ReadPixels(bitmap.width, bitmap.height, background, surface, ink, foregrounds) { y, row ->
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
        val probes = listOf(0 to 0, bitmap.width / 2 to bitmap.height / 2, bitmap.width - 1 to bitmap.height - 1)
            .map { (x, y) -> "($x,$y)=${bitmap.getPixel(x, y)}" }
        record("pixels=$pixels expectedBackground=$background expectedSurface=$surface expectedInk=$ink " +
            "expectedForegrounds=${foregrounds.sorted()} probes=$probes")
        val status = pr18CaptureStatus(protected, flagsBefore and WindowManager.LayoutParams.FLAG_SECURE != 0, secureAfter, copyResult,
            pixels.count, pixels.black, pixels.transparent, pixels.white)
        if (status != Pr18CaptureStatus.SECURE_REDACTED) pr18ValidateProductPixels(pixels, Pr18CaptureMethod.WINDOW_PIXEL_COPY)
        assertEquals("own-window capture geometry width", crop.width(), bitmap.width)
        assertEquals("own-window capture geometry height", crop.height(), bitmap.height)
        val capture = pr18PersistCapturedPng(attempt, provenance(status), bitmap, pixels, ::record)
        outcome = status.name.lowercase()
        return Pr18WindowCaptureResult(capture = capture)
    } catch (failure: Throwable) {
        record("outcome=$outcome strictEvidence=blocked callback=$callback type=${failure.javaClass.name} message=${failure.message}")
        return Pr18WindowCaptureResult(failure = failure)
    } finally {
        if (!drawingEnabledBefore) HardwareRendererCompat.setDrawingEnabled(false)
        val flagsAfter = compose.runOnIdle { window.attributes.flags }
        record("flagsBefore=$flagsBefore flagsAfter=$flagsAfter callback=$callback drawObserved=$drawn frameCommitted=$committed outcome=$outcome")
        assertEquals("capture never changes owner secure flag", flagsBefore and WindowManager.LayoutParams.FLAG_SECURE,
            flagsAfter and WindowManager.LayoutParams.FLAG_SECURE)
        if (callback != null) copiedBitmap?.recycle()
    }
}
