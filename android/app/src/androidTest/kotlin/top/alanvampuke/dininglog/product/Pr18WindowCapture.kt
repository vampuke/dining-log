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
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import org.junit.Assert.*

// The caller retains this receipt through its behavior gates, then MUST requireValid().
// A denial is diagnostic evidence only; it never satisfies the PNG requirement.
internal class Pr18CaptureReceipt(private val failures: List<Throwable>) {
    fun requireValid() {
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }
}

// Row-sized storage bounds allocation while checking every real Activity pixel.
// Use the same content thresholds as the strict own-window probe below.
internal fun pr18ValidateActivityBitmap(bitmap: Bitmap, width: Int, height: Int,
    background: Int, surface: Int, ink: Int, record: (String) -> Unit) {
    assertTrue("normal capture has positive actual geometry", width > 0 && height > 0)
    assertEquals("normal capture geometry width", width, bitmap.width)
    assertEquals("normal capture geometry height", height, bitmap.height)
    val row = IntArray(width)
    var opaque = 0L; var black = 0L; var transparent = 0L; var white = 0L
    var backgroundPixels = 0L; var surfacePixels = 0L; var inkPixels = 0L
    for (y in 0 until height) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        for (pixel in row) {
            if (pixel ushr 24 == 255) opaque++
            if (pixel == 0xFF000000.toInt()) black++
            if (pixel ushr 24 == 0) transparent++
            if (pixel == 0xFFFFFFFF.toInt()) white++
            if (pixel == background) backgroundPixels++
            if (pixel == surface) surfacePixels++
            if (pixel == ink) inkPixels++
        }
    }
    val count = width.toLong() * height
    record("activity-pixels=${count} opaque=$opaque black=$black transparent=$transparent white=$white " +
        "expectedBackground=$background backgroundPixels=$backgroundPixels expectedSurface=$surface surfacePixels=$surfacePixels " +
        "expectedInk=$ink inkPixels=$inkPixels dimensions=${width}x$height")
    assertTrue("normal capture rejects blank/transparent/white placeholders", black + transparent < count * .9 && white != count)
    assertTrue("real Activity palette background/surface painted", backgroundPixels + surfacePixels > 250)
    assertTrue("real Activity palette occupies content", backgroundPixels + surfacePixels >= count / 20)
    assertTrue("real Activity palette ink painted", inkPixels > 10)
    assertTrue("real Activity opaque content", opaque > 250)
}

internal fun pr18VerifyActivityPng(file: File, width: Int, height: Int): String {
    val bytes = file.readBytes()
    check(bytes.size > 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }) {
        "Normal Activity capture did not produce a real nonempty PNG"
    }
    val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Normal PNG cannot be decoded" }
    try {
        assertEquals("written PNG geometry width", width, decoded.width)
        assertEquals("written PNG geometry height", height, decoded.height)
    } finally { decoded.recycle() }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun pr18OwnWindowCapture(
    evidence: Pr18Evidence, node: SemanticsNode, window: Window, file: File,
    protected: Boolean, background: Int, surface: Int, ink: Int,
): Throwable? {
    val compose = evidence.compose
    val view = (node.root as ViewRootForTest).view
    val decor = window.decorView
    val handler = Handler(Looper.getMainLooper())
    val flagsBefore = compose.runOnIdle { window.attributes.flags }
    var callback: Int? = null
    var drawn = false
    var committed = false
    var outcome = "capture-failed"
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
        val complete = CountDownLatch(1)
        record("requestedPixelCopyCrop=$requestCrop bitmap=${bitmap.width}x${bitmap.height} attempt=1")
        PixelCopy.request(window, requestCrop, bitmap, { result -> callback = result; complete.countDown() }, handler)
        check(complete.await(1, TimeUnit.SECONDS)) { "Own-window PixelCopy callback timeout" }
        record("callbackResult=$callback submittedCropAfterPlatformOffsets=$requestCrop")
        if (callback != PixelCopy.SUCCESS) {
            outcome = if (protected && callback == PixelCopy.ERROR_SOURCE_INVALID) "expected-secure-denied" else "capture-failed"
            error("$outcome: PixelCopy result=$callback; strict PNG evidence blocked")
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val opaque = pixels.count { it ushr 24 == 255 }
        val black = pixels.count { it == 0xFF000000.toInt() }
        val transparent = pixels.count { it ushr 24 == 0 }
        val white = pixels.count { it == 0xFFFFFFFF.toInt() }
        val backgroundPixels = pixels.count { it == background }
        val surfacePixels = pixels.count { it == surface }
        val inkPixels = pixels.count { it == ink }
        val probes = listOf(0 to 0, bitmap.width / 2 to bitmap.height / 2, bitmap.width - 1 to bitmap.height - 1)
            .map { (x, y) -> "($x,$y)=${bitmap.getPixel(x, y)}" }
        record("pixels=${pixels.size} opaque=$opaque black=$black transparent=$transparent white=$white " +
            "expectedBackground=$background backgroundPixels=$backgroundPixels expectedSurface=$surface surfacePixels=$surfacePixels " +
            "expectedInk=$ink inkPixels=$inkPixels probes=$probes")
        // Save only the actual successful copy, including blank results as rejected diagnostics.
        file.outputStream().use { assertTrue("screenshot PNG written", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        record("png=${file.name} sha256=$hash dimensions=${bitmap.width}x${bitmap.height}")
        val blank = black + transparent >= pixels.size * .9 || white == pixels.size
        outcome = if (protected && blank) "expected-secure-denied" else "capture-failed"
        check(!blank) { "$outcome: blank successful copy; strict PNG evidence blocked" }
        assertTrue("real own-window palette background/surface painted", backgroundPixels + surfacePixels > 250)
        assertTrue("real own-window palette occupies content", backgroundPixels + surfacePixels >= pixels.size / 20)
        assertTrue("real own-window palette ink painted", inkPixels > 10)
        assertTrue("real own-window opaque content", opaque > 250)
        assertEquals("own-window capture geometry width", crop.width(), bitmap.width)
        assertEquals("own-window capture geometry height", crop.height(), bitmap.height)
        outcome = "valid-real-own-window-png"
    } catch (failure: Throwable) {
        record("outcome=$outcome strictEvidence=blocked callback=$callback type=${failure.javaClass.name} message=${failure.message}")
        return failure
    } finally {
        if (!drawingEnabledBefore) HardwareRendererCompat.setDrawingEnabled(false)
        val flagsAfter = compose.runOnIdle { window.attributes.flags }
        record("flagsBefore=$flagsBefore flagsAfter=$flagsAfter callback=$callback drawObserved=$drawn frameCommitted=$committed outcome=$outcome")
        assertEquals("capture never changes owner secure flag", flagsBefore and WindowManager.LayoutParams.FLAG_SECURE,
            flagsAfter and WindowManager.LayoutParams.FLAG_SECURE)
    }
    return null
}
