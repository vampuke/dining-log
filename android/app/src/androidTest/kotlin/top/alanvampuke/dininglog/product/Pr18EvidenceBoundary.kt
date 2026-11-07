package top.alanvampuke.dininglog.product

import java.security.MessageDigest

// Platform adapters supply observations from the actual owner. Kept free of
// Android types so the same acceptance boundary can run in the bounded JVM harness.
internal enum class Pr18CopyResult { SUCCESS, SOURCE_INVALID, UNKNOWN, TIMEOUT, SOURCE_NO_DATA, DESTINATION_INVALID }
internal enum class Pr18CaptureStatus { NORMAL_PNG, SECURE_PNG, SECURE_DENIED, SECURE_REDACTED }
internal enum class Pr18CaptureMethod { ACTIVITY_IMAGE, WINDOW_PIXEL_COPY }

internal data class Pr18Point(val x: Float, val y: Float)
internal data class Pr18Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun translate(offset: Pr18Point) = Pr18Rect(left + offset.x, top + offset.y, right + offset.x, bottom + offset.y)
    fun contains(point: Pr18Point) = point.x >= left && point.x < right && point.y >= top && point.y < bottom
    fun isValid() = listOf(left, top, right, bottom).all { it.isFinite() } && right > left && bottom > top
}

internal enum class Pr18TouchMode { VIEW_DISPATCH, SCREEN_INPUT }
internal data class Pr18ScreenTarget(
    val nodeId: Int, val accessibilityNodeId: Int, val owner: String, val rootOwner: String,
    val rootMatchesOwner: Boolean, val attached: Boolean, val shown: Boolean, val focused: Boolean,
    val windowId: Int, val observedWindowId: Int, val accessibilityWindowId: Int, val accessibilityVisible: Boolean,
    val full: Pr18Rect, val semanticFull: Pr18Rect, val clipped: Pr18Rect, val visible: Pr18Rect,
    val accessibility: Pr18Rect, val point: Pr18Point, val displayId: Int,
    val actualFont: Float, val style: String, val dark: Boolean, val mode: Pr18TouchMode,
)

internal fun pr18RequireScreenTarget(target: Pr18ScreenTarget, overlays: List<Pr18Rect>): Pr18Point {
    check(target.mode == Pr18TouchMode.SCREEN_INPUT) { "View dispatch cannot prove physical screen delivery" }
    check(target.attached && target.shown && target.focused && target.rootMatchesOwner && target.owner == target.rootOwner) {
        "Screen target has a wrong, hidden or unavailable owner/root"
    }
    check(target.windowId >= 0 && target.observedWindowId == target.windowId && target.accessibilityWindowId == target.windowId &&
        target.accessibilityNodeId == target.nodeId) { "Screen target accessibility owner/provenance unavailable or mismatched" }
    check(target.displayId == 0) { "Physical input is unavailable for this display" }
    check(target.point.x.isFinite() && target.point.y.isFinite() &&
        listOf(target.full, target.clipped, target.visible, target.accessibility).all { it.isValid() } &&
        target.full == target.semanticFull) { "Screen target bounds/provenance invalid" }
    check(target.accessibilityVisible && listOf(target.full, target.clipped, target.visible, target.accessibility).all { it.contains(target.point) }) {
        "Screen target is hidden, clipped or covered"
    }
    check(overlays.all { it.isValid() } && overlays.none { it.contains(target.point) }) { "Screen target is occluded" }
    return target.point
}

internal class Pr18TouchReceipt(val target: Pr18ScreenTarget, val downAccepted: Boolean, val upAccepted: Boolean) {
    init {
        check(target.mode == Pr18TouchMode.SCREEN_INPUT && downAccepted && upAccepted) { "Physical screen input injection unavailable/failed" }
    }
    fun requireCallbackDelta(before: Int, after: Int, expected: Int = 1) {
        check(after - before == expected) { "Physical target callback delta=${after - before}, expected=$expected" }
    }
}

internal fun pr18WindowToScreen(bounds: Pr18Rect, rootOnScreen: Pr18Point, rootInWindow: Pr18Point): Pr18Rect =
    bounds.translate(Pr18Point(rootOnScreen.x - rootInWindow.x, rootOnScreen.y - rootInWindow.y))
internal fun pr18LocalToScreen(bounds: Pr18Rect, rootOnScreen: Pr18Point): Pr18Rect = bounds.translate(rootOnScreen)
internal fun pr18ClearsIme(saveScreen: Pr18Rect, keyboardScreen: List<Pr18Rect>, tolerance: Float): Boolean =
    keyboardScreen.isNotEmpty() && keyboardScreen.all { saveScreen.bottom <= it.top + tolerance }
internal fun pr18ImeRectangles(observedScreenRectangles: List<Pr18Rect>): List<Pr18Rect> = observedScreenRectangles.toList()

internal data class Pr18CaptureSource(
    val testMethod: String, val caseId: String, val step: Int, val name: String,
    val method: Pr18CaptureMethod, val owner: String, val root: String, val token: String,
    val api: Int, val requestedFont: Float, val actualFont: Float, val density: Float,
    val style: String, val dark: Boolean, val geometry: String, val flagsBefore: Int,
    val secureBefore: Boolean, val fixtureLabel: String? = null,
)

internal data class Pr18CaptureProvenance(
    val source: Pr18CaptureSource, val status: Pr18CaptureStatus,
    val flagsAfter: Int, val secureAfter: Boolean, val platformResult: Int?, val copyGeometry: String,
)

internal data class Pr18DecodedPixels(val width: Int, val height: Int, val pixelSha256: String)
internal fun Pr18PixelFacts.decodedPixels() = Pr18DecodedPixels(width, height, pixelSha256)

internal class Pr18PngSeal internal constructor(
    val provenance: Pr18CaptureProvenance, private val captured: Pr18DecodedPixels, val encodedSha256: String,
) {
    private val commitment = "$provenance\npixels=$captured\nencodedSha256=$encodedSha256"
    val metadata = "pr18-capture-v1\n$commitment\nbindingSha256=${pr18Sha256(commitment.toByteArray(Charsets.UTF_8))}\n"

    fun requireReadback(bytes: ByteArray?, metadataReadback: String, claimed: Pr18CaptureProvenance,
        decode: (ByteArray) -> Pr18DecodedPixels?): String {
        check(claimed == provenance) { "PNG source/provenance differs from the original capture" }
        check(metadataReadback == metadata) { "PNG metadata differs from the original capture commitment" }
        val actual = requireNotNull(bytes) { "PNG readback is null" }
        pr18RequirePngSignature(actual)
        check(pr18Sha256(actual) == encodedSha256) { "PNG readback differs from the original encoded capture" }
        val decoded = requireNotNull(decode(actual)) { "PNG readback cannot be decoded" }
        check(decoded == captured) { "PNG readback pixels/geometry differ from the originally captured bitmap" }
        return encodedSha256
    }
}

internal fun pr18RequireRuntimeCapture(provenance: Pr18CaptureProvenance) {
    check(provenance.source.fixtureLabel == null) { "Explicit control fixture cannot be runtime capture evidence" }
}

internal fun pr18RequireCaptureSource(expectedSource: Pr18CaptureSource, provenance: Pr18CaptureProvenance) {
    check(provenance.source == expectedSource) { "PNG source/provenance differs from the observed capture attempt" }
}

private fun pr18RequirePngSignature(bytes: ByteArray) {
    check(bytes.size > 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }) {
        "Capture did not produce a real nonempty PNG"
    }
}

internal fun pr18SealPng(expectedSource: Pr18CaptureSource, provenance: Pr18CaptureProvenance, captured: Pr18DecodedPixels?,
    encoded: ByteArray?, compressionSucceeded: Boolean, decode: (ByteArray) -> Pr18DecodedPixels?): Pr18PngSeal {
    pr18RequireCaptureSource(expectedSource, provenance)
    check(compressionSucceeded) { "PNG compression failed" }
    val pixels = requireNotNull(captured) { "Captured bitmap is null" }
    val bytes = requireNotNull(encoded) { "Encoded PNG is null" }
    pr18RequirePngSignature(bytes)
    val decoded = requireNotNull(decode(bytes)) { "Encoded PNG cannot be decoded" }
    check(pixels == decoded) { "Encoded PNG pixels/geometry differ from the originally captured bitmap" }
    return Pr18PngSeal(provenance, pixels, pr18Sha256(bytes))
}

internal class Pr18CaptureRecord(val provenance: Pr18CaptureProvenance, val pngSha256: String?,
    private val verifyReadback: () -> Unit) {
    fun requireValid() = verifyReadback()
}

// Freeze the verified capture records, then re-check storage after the original
// behavior gates. A later font/window change must not rewrite their provenance.
internal class Pr18CaptureReceipt(records: List<Pr18CaptureRecord>, failures: List<Throwable>) {
    val captures = records.toList()
    private val failures = failures.toList()
    fun requireValid() {
        val errors = failures + captures.mapNotNull { runCatching { it.requireValid() }.exceptionOrNull() }
        if (errors.isNotEmpty()) {
            val first = errors.first()
            errors.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
        check(captures.isNotEmpty()) { "Capture receipt has no verified product owners" }
    }
}

internal data class Pr18PixelFacts(
    val width: Int, val height: Int, val opaque: Long, val black: Long,
    val transparent: Long, val white: Long, val background: Long, val surface: Long,
    val foreground: Long, val ink: Long, val distinctForegrounds: Int, val pixelSha256: String,
) { val count get() = width.toLong() * height }

internal fun pr18Sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal fun pr18ReadPixels(width: Int, height: Int, background: Int, surface: Int, ink: Int,
    foregrounds: Set<Int>, readRow: (Int, IntArray) -> Unit): Pr18PixelFacts {
    check(width > 0 && height > 0) { "Capture has no positive actual geometry" }
    val expected = foregrounds - setOf(background, surface)
    val row = IntArray(width)
    val bytes = ByteArray(width * 4)
    val digest = MessageDigest.getInstance("SHA-256")
    var opaque = 0L; var black = 0L; var transparent = 0L; var white = 0L
    var backgroundPixels = 0L; var surfacePixels = 0L; var foregroundPixels = 0L; var inkPixels = 0L
    for (y in 0 until height) {
        readRow(y, row)
        for ((x, pixel) in row.withIndex()) {
            if (pixel ushr 24 == 255) opaque++
            if (pixel == 0xFF000000.toInt()) black++
            if (pixel ushr 24 == 0) transparent++
            if (pixel == 0xFFFFFFFF.toInt()) white++
            if (pixel == background) backgroundPixels++
            if (pixel == surface) surfacePixels++
            if (pixel in expected) foregroundPixels++
            if (pixel == ink) inkPixels++
            for (channel in 0..3) bytes[x * 4 + channel] = (pixel ushr (24 - channel * 8)).toByte()
        }
        digest.update(bytes)
    }
    return Pr18PixelFacts(width, height, opaque, black, transparent, white, backgroundPixels, surfacePixels,
        foregroundPixels, inkPixels, expected.size, digest.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0') })
}

internal fun pr18ValidateProductPixels(pixels: Pr18PixelFacts, method: Pr18CaptureMethod) {
    check(pixels.distinctForegrounds > 0) { "Product capture expects a distinct palette foreground" }
    check(pixels.black + pixels.transparent < pixels.count * .9 && pixels.white != pixels.count) {
        "Product capture rejects blank/transparent/white placeholders"
    }
    check(pixels.background + pixels.surface > 250) { "Real product palette background/surface painted" }
    check(pixels.background + pixels.surface >= pixels.count / 20) { "Real product palette occupies content" }
    check(pixels.foreground > 10) {
        "Real product palette foreground painted: method=$method foreground=${pixels.foreground} ink=${pixels.ink}"
    }
    check(pixels.opaque > 250) { "Real product opaque content" }
}

internal fun <T> pr18ProductCaptureOwners(host: T, dialogs: List<T>): List<T> = dialogs.ifEmpty { listOf(host) }

internal fun pr18CaptureStatus(protected: Boolean, secureBefore: Boolean, secureAfter: Boolean,
    result: Pr18CopyResult?, count: Long = 0, black: Long = 0, transparent: Long = 0, white: Long = 0): Pr18CaptureStatus {
    check(secureBefore == protected && secureAfter == protected) { "Capture changed/mismatched owner protection" }
    if (protected && result == Pr18CopyResult.SOURCE_INVALID) return Pr18CaptureStatus.SECURE_DENIED
    check(result == Pr18CopyResult.SUCCESS) { "PixelCopy result=$result; strict PNG evidence blocked" }
    check(count > 0) { "Capture has no actual pixels" }
    // Only a wholly black/transparent successful protected copy is an observed
    // redaction. White, background-only and partially blank copies get no exemption.
    if (protected && black + transparent == count) return Pr18CaptureStatus.SECURE_REDACTED
    check(black + transparent < count * .9 && white != count) { "Blank successful copy; strict PNG evidence blocked" }
    return if (protected) Pr18CaptureStatus.SECURE_PNG else Pr18CaptureStatus.NORMAL_PNG
}
