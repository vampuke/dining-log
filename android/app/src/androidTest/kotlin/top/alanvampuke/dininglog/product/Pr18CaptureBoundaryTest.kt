package top.alanvampuke.dininglog.product

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.ThemeStyle
import top.alanvampuke.dininglog.core.UpdateInstallState
import top.alanvampuke.dininglog.feature.settings.UpdateDialog

// Decoder/storage controls use the unchanged old artifact, explicitly labelled
// CONTROL. Negative PNGs stay in memory and never enter runtime screenshot storage.
@RunWith(AndroidJUnit4::class)
class Pr18CaptureBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>()
    private lateinit var evidence: Pr18Evidence
    private val background = 0xFF1C1915.toInt()
    private val surface = 0xFF26211B.toInt()
    private val ink = 0xFFF5F1E8.toInt()
    private val foregrounds = setOf(ink, 0xFFB3A996.toInt(), 0xFF998F7E.toInt(), 0xFFE56E44.toInt(), 0xFFFF9398.toInt())

    @Before fun prepare() {
        evidence = Pr18Evidence(compose, ThemeStyle.INK_PAPER, false)
        evidence.configure(1f)
        evidence.render {}
    }
    @After fun restore() { evidence.close() }

    private fun originalBytes(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets
        .open("pr18-capture-controls/legacy-real-accent-api26.png").use { it.readBytes() }.also {
            assertEquals("Unchanged CONTROL from run 42372736617",
                "6cc97b08f0455ec77f5c823d96a69b1d5a2f4e5e41be293c918737adc7de0c71", pr18Sha256(it))
        }

    private fun pixels(bitmap: Bitmap) = pr18ReadPixels(bitmap.width, bitmap.height, background, surface, ink, foregrounds) { y, row ->
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
    }

    private fun originalPixels(): Pr18PixelFacts {
        val bytes = originalBytes()
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        return try { pixels(bitmap) } finally { bitmap.recycle() }
    }

    private fun control(method: Pr18CaptureMethod) = Pr18CaptureProvenance(Pr18CaptureSource(
        "Pr18CaptureBoundaryTest.explicitLegacyControl", "legacy-control", 1, "legacy-real-accent-api26",
        method, "CONTROL-owner", "CONTROL-root", "CONTROL-token", 26, 1f, 1f, 2.625f,
        "INK_PAPER", true, "CONTROL legacy PNG 945x1680", 0, false,
        "CONTROL original run 42372736617; never candidate runtime evidence"),
        Pr18CaptureStatus.NORMAL_PNG, 0, false, null, "CONTROL original PNG")

    private fun rejects(reason: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull("CONTROL must reject: $reason", failure)
        assertTrue("CONTROL rejected for the relevant reason: $failure", failure!!.message.orEmpty().contains(reason))
    }

    @Test fun originalAndroidPngAcceptsAccentOnlyProductInBothCaptureMethods() {
        val bytes = originalBytes()
        val pixels = originalPixels()
        assertEquals(945, pixels.width); assertEquals(1680, pixels.height)
        assertEquals(0L, pixels.ink); assertEquals(16838L, pixels.foreground)
        for (method in Pr18CaptureMethod.values()) {
            pr18ValidateProductPixels(pixels, method)
            val source = control(method)
            val seal = pr18SealPng(source.source, source, pixels.decodedPixels(), bytes, true, ::pr18DecodePng)
            assertEquals(pr18Sha256(bytes), seal.requireReadback(bytes, seal.metadata, source, ::pr18DecodePng))
        }
    }

    @Test fun androidReadbackRejectsChangedBytesAndSameSizeTransparentSubstitution() {
        val bytes = originalBytes()
        val pixels = originalPixels()
        val transparent = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        val substitute = try {
            ByteArrayOutputStream().also { assertTrue(transparent.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { transparent.recycle() }
        assertEquals(pixels.width, pr18DecodePng(substitute)!!.width)
        assertEquals(pixels.height, pr18DecodePng(substitute)!!.height)
        assertNotEquals(pixels.pixelSha256, pr18DecodePng(substitute)!!.pixelSha256)
        for (method in Pr18CaptureMethod.values()) {
            val source = control(method)
            val seal = pr18SealPng(source.source, source, pixels.decodedPixels(), bytes, true, ::pr18DecodePng)
            seal.requireReadback(bytes, seal.metadata, source, ::pr18DecodePng)
            rejects("original encoded capture") { seal.requireReadback(bytes + byteArrayOf(0), seal.metadata, source, ::pr18DecodePng) }
            rejects("original encoded capture") { seal.requireReadback(substitute, seal.metadata, source, ::pr18DecodePng) }
        }
    }

    @Test fun androidReadbackRejectsWrongSourceAndMetadata() {
        val bytes = originalBytes()
        for (method in Pr18CaptureMethod.values()) {
            val source = control(method)
            val seal = pr18SealPng(source.source, source, originalPixels().decodedPixels(), bytes, true, ::pr18DecodePng)
            seal.requireReadback(bytes, seal.metadata, source, ::pr18DecodePng)
            val wrong = listOf(source.copy(source = source.source.copy(owner = "WRONG-owner")),
                source.copy(source = source.source.copy(root = "WRONG-root")),
                source.copy(source = source.source.copy(token = "WRONG-token")),
                source.copy(source = source.source.copy(testMethod = "WRONG-method")),
                source.copy(source = source.source.copy(method = Pr18CaptureMethod.values().first { it != method })),
                source.copy(source = source.source.copy(actualFont = 2f)),
                source.copy(source = source.source.copy(style = "QUIET_GRID")),
                source.copy(source = source.source.copy(dark = false)),
                source.copy(source = source.source.copy(geometry = "WRONG-geometry")),
                source.copy(status = Pr18CaptureStatus.SECURE_REDACTED),
                source.copy(copyGeometry = "WRONG-copy-outcome"))
            for (claimed in wrong) rejects("source/provenance") { seal.requireReadback(bytes, seal.metadata, claimed, ::pr18DecodePng) }
            for (claimed in wrong.filter { it.source != source.source }) rejects("observed capture attempt") {
                pr18SealPng(source.source, claimed, originalPixels().decodedPixels(), bytes, true, ::pr18DecodePng)
            }
            rejects("metadata") { seal.requireReadback(bytes, seal.metadata + "CONTROL tamper", source, ::pr18DecodePng) }
            var readback = seal.metadata
            val receipt = Pr18CaptureReceipt(listOf(Pr18CaptureRecord(source, seal.encodedSha256) {
                seal.requireReadback(bytes, readback, source, ::pr18DecodePng)
            }), emptyList())
            receipt.requireValid()
            readback += "CONTROL changed after behavior gates"
            rejects("metadata") { receipt.requireValid() }
        }
    }

    @Test fun androidPngBoundaryRejectsNullDecodeCompressAndPixelMismatch() {
        val bytes = originalBytes()
        val source = control(Pr18CaptureMethod.WINDOW_PIXEL_COPY)
        val pixels = originalPixels().decodedPixels()
        rejects("bitmap is null") { pr18SealPng(source.source, source, null, bytes, true, ::pr18DecodePng) }
        rejects("PNG is null") { pr18SealPng(source.source, source, pixels, null, true, ::pr18DecodePng) }
        rejects("compression failed") { pr18SealPng(source.source, source, pixels, bytes, false, ::pr18DecodePng) }
        val undecodable = bytes.take(8).toByteArray() + ByteArray(16)
        assertNull(pr18DecodePng(undecodable))
        rejects("cannot be decoded") { pr18SealPng(source.source, source, pixels, undecodable, true, ::pr18DecodePng) }
        rejects("originally captured bitmap") { pr18SealPng(source.source, source, pixels.copy(pixelSha256 = "WRONG-pixels"), bytes, true, ::pr18DecodePng) }
        val seal = pr18SealPng(source.source, source, pixels, bytes, true, ::pr18DecodePng)
        rejects("readback is null") { seal.requireReadback(null, seal.metadata, source, ::pr18DecodePng) }
        rejects("cannot be decoded") { seal.requireReadback(bytes, seal.metadata, source) { null } }
        rejects("originally captured bitmap") { seal.requireReadback(bytes, seal.metadata, source) { pixels.copy(width = pixels.width + 1) } }
    }

    @Test fun labelledLegacyFixtureCannotEnterRuntimePersistence() {
        val bytes = originalBytes()
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val source = control(Pr18CaptureMethod.ACTIVITY_IMAGE)
        val file = File(InstrumentationRegistry.getInstrumentation().context.cacheDir, "CONTROL-${UUID.randomUUID()}.png")
        try {
            rejects("control fixture") { pr18PersistCapturedPng(Pr18CaptureAttempt(file, source.source), source, bitmap, pixels(bitmap)) {} }
            assertFalse("CONTROL cannot write a runtime PNG", file.exists())
            assertFalse("CONTROL cannot write runtime metadata", File(file.parentFile, "${file.nameWithoutExtension}.capture.txt").exists())
        } finally { bitmap.recycle() }
    }

    @Test fun independentRealDialogCapturesProductOwnerWhileBlankHostKeepsFlagChecks() {
        // This is an actual production Dialog capture, not an image fixture.
        evidence.render { UpdateDialog(UpdateInstallState(pr18Release()), {}, {}, {}, {}, {}) }
        val host = System.identityHashCode(compose.activity.window).toString()
        val receipt = evidence.probeWindows("dialog-owner-regression", protected = false, includeDialogs = true)
        receipt.requireValid()
        assertEquals("Only the real product Dialog expects foreground", 1, receipt.captures.size)
        val captured = receipt.captures.single()
        assertNotEquals(host, captured.provenance.source.owner)
        assertEquals(Pr18CaptureMethod.WINDOW_PIXEL_COPY, captured.provenance.source.method)
        assertEquals(Pr18CaptureStatus.NORMAL_PNG, captured.provenance.status)
        assertNotNull(captured.pngSha256)
        receipt.requireValid()
    }
}
