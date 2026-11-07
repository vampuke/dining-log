package top.alanvampuke.dininglog.product

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

// Explicit control observations, never candidate runtime captures/screenshots.
class Pr18EvidenceBoundaryJvmTest {
    private val background = 0xFF1C1915.toInt()
    private val surface = 0xFF26211B.toInt()
    private val ink = 0xFFF5F1E8.toInt()
    private val foregrounds = setOf(ink, 0xFFB3A996.toInt(), 0xFF998F7E.toInt(), 0xFFE56E44.toInt(), 0xFFFF9398.toInt())
    private fun realPixels(): Pr18PixelFacts {
        val file = File("android/app/src/androidTest/assets/pr18-capture-controls/legacy-real-accent-api26.png")
        assertEquals("Original artifact bytes, not a replacement runtime frame",
            "6cc97b08f0455ec77f5c823d96a69b1d5a2f4e5e41be293c918737adc7de0c71", pr18Sha256(file.readBytes()))
        val image = ImageIO.read(file)
        return pr18ReadPixels(image.width, image.height, background, surface, ink, foregrounds) { y, row ->
            image.getRGB(0, y, image.width, 1, row, 0, image.width)
        }
    }
    private fun realBytes() = File("android/app/src/androidTest/assets/pr18-capture-controls/legacy-real-accent-api26.png").readBytes()
    private fun decode(bytes: ByteArray): Pr18DecodedPixels? {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        return pr18ReadPixels(image.width, image.height, background, surface, ink, foregrounds) { y, row ->
            image.getRGB(0, y, image.width, 1, row, 0, image.width)
        }.decodedPixels()
    }
    private fun provenance() = Pr18CaptureProvenance(Pr18CaptureSource(
        "Pr18EvidenceBoundaryJvmTest.explicitLegacyControl", "legacy-control", 1, "legacy-real-accent-api26",
        Pr18CaptureMethod.ACTIVITY_IMAGE, "CONTROL-owner", "CONTROL-root", "CONTROL-token", 26,
        1f, 1f, 2.625f, "INK_PAPER", true, "CONTROL legacy fixture 945x1680", 0, false,
        "CONTROL original run 42372736617; never a candidate runtime screenshot"),
        Pr18CaptureStatus.NORMAL_PNG, 0, false, null, "CONTROL original PNG")
    private fun screenTarget() = Pr18ScreenTarget(1, 1, "CONTROL-owner", "CONTROL-owner", true, true, true, true,
        7, 7, 7, true, Pr18Rect(100f, 100f, 148f, 148f), Pr18Rect(100f, 100f, 148f, 148f),
        Pr18Rect(100f, 100f, 148f, 148f), Pr18Rect(0f, 0f, 600f, 800f), Pr18Rect(100f, 100f, 148f, 148f),
        Pr18Point(124f, 124f), 0, 1f, "INK_PAPER", false, Pr18TouchMode.SCREEN_INPUT)
    private fun rejects(block: () -> Unit) {
        assertTrue("Explicit negative control must fail the real boundary", runCatching(block).isFailure)
    }
    @Test fun secureSourceDenialKeepsProtectionAndNeedsNoPng() {
        assertEquals(Pr18CaptureStatus.SECURE_DENIED,
            pr18CaptureStatus(true, true, true, Pr18CopyResult.SOURCE_INVALID))
    }

    @Test fun observedSecureBlackCopyIsRedactedEvidence() {
        assertEquals(Pr18CaptureStatus.SECURE_REDACTED,
            pr18CaptureStatus(true, true, true, Pr18CopyResult.SUCCESS, count = 4, black = 4))
    }

    @Test fun observedSecureTransparentCopyIsRedactedEvidence() {
        assertEquals(Pr18CaptureStatus.SECURE_REDACTED,
            pr18CaptureStatus(true, true, true, Pr18CopyResult.SUCCESS, count = 4, transparent = 4))
    }

    @Test fun unknownNullTimeoutAndNoDataNeverBecomeSecureDenials() {
        for (result in listOf(null, Pr18CopyResult.UNKNOWN, Pr18CopyResult.TIMEOUT,
            Pr18CopyResult.SOURCE_NO_DATA, Pr18CopyResult.DESTINATION_INVALID)) {
            rejects { pr18CaptureStatus(true, true, true, result) }
        }
    }

    @Test fun denialRequiresIntactSecureOwner() {
        rejects { pr18CaptureStatus(true, true, false, Pr18CopyResult.SOURCE_INVALID) }
        rejects { pr18CaptureStatus(true, false, true, Pr18CopyResult.SOURCE_INVALID) }
        rejects { pr18CaptureStatus(false, false, false, Pr18CopyResult.SOURCE_INVALID) }
    }

    @Test fun unprotectedBlankAndSecureWhiteCopiesFail() {
        rejects { pr18CaptureStatus(false, false, false, Pr18CopyResult.SUCCESS, count = 4, black = 4) }
        rejects { pr18CaptureStatus(false, false, false, Pr18CopyResult.SUCCESS, count = 4, transparent = 4) }
        rejects { pr18CaptureStatus(true, true, true, Pr18CopyResult.SUCCESS, count = 4, white = 4) }
    }

    @Test fun independentDialogCaptureSelectsTheProductOwner() {
        assertEquals(listOf("actual-dialog-product"),
            pr18ProductCaptureOwners("intentionally-blank-test-host", listOf("actual-dialog-product")))
    }

    @Test fun absentDialogStillRequiresTheHostProductCapture() {
        assertEquals(listOf("host-product"), pr18ProductCaptureOwners("host-product", emptyList<String>()))
    }

    @Test fun realAccentOnlyPngPassesTheOwnWindowProductBoundary() {
        val pixels = realPixels()
        assertEquals(0L, pixels.ink)
        assertEquals(16838L, pixels.foreground)
        pr18ValidateProductPixels(pixels, Pr18CaptureMethod.ACTIVITY_IMAGE)
        pr18ValidateProductPixels(pixels, Pr18CaptureMethod.WINDOW_PIXEL_COPY)
    }

    @Test fun legalSecondaryOnlyProductPassesBothCaptureMethods() {
        // Explicit in-memory palette control; never encoded or stored as a screenshot.
        val pixels = pr18ReadPixels(32, 32, background, surface, ink, foregrounds) { y, row ->
            row.fill(if (y == 0) 0xFFB3A996.toInt() else background)
        }
        assertEquals(0L, pixels.ink)
        for (method in Pr18CaptureMethod.values()) pr18ValidateProductPixels(pixels, method)
    }

    @Test fun blankProductContentStillFailsBothCaptureMethods() {
        val pixels = pr18ReadPixels(32, 32, background, surface, ink, foregrounds) { _, row -> row.fill(background) }
        for (method in Pr18CaptureMethod.values()) rejects { pr18ValidateProductPixels(pixels, method) }
    }

    @Test fun sameSizeTransparentSubstituteCannotBeAuthenticated() {
        val source = provenance()
        val bytes = realBytes()
        val original = realPixels().decodedPixels()
        val seal = pr18SealPng(source.source, source, original, bytes, true, ::decode)
        assertEquals(pr18Sha256(bytes), seal.requireReadback(bytes, seal.metadata, source, ::decode))
        // Explicit negative PNG exists only in memory; no screenshot/artifact write.
        val substitute = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        assertEquals(original.width, decode(substitute)!!.width)
        assertEquals(original.height, decode(substitute)!!.height)
        rejects { seal.requireReadback(substitute, seal.metadata, source, ::decode) }
    }

    @Test fun wrongSourceOrProvenanceCannotReuseTheRealPngReceipt() {
        val source = provenance()
        val bytes = realBytes()
        val seal = pr18SealPng(source.source, source, realPixels().decodedPixels(), bytes, true, ::decode)
        val wrongSources = listOf(
            source.copy(source = source.source.copy(owner = "WRONG-owner")),
            source.copy(source = source.source.copy(root = "WRONG-root")),
            source.copy(source = source.source.copy(token = "WRONG-token")),
            source.copy(source = source.source.copy(testMethod = "WRONG-testMethod")),
            source.copy(source = source.source.copy(method = Pr18CaptureMethod.WINDOW_PIXEL_COPY)),
            source.copy(source = source.source.copy(actualFont = 2f)),
            source.copy(source = source.source.copy(style = "QUIET_GRID")),
            source.copy(source = source.source.copy(dark = false)),
            source.copy(source = source.source.copy(geometry = "WRONG-geometry")),
            source.copy(status = Pr18CaptureStatus.SECURE_REDACTED),
        )
        for (wrong in wrongSources) rejects { seal.requireReadback(bytes, seal.metadata, wrong, ::decode) }
        rejects { seal.requireReadback(bytes, seal.metadata + "tampered metadata", source, ::decode) }
        assertEquals(source, seal.provenance)
    }

    @Test fun explicitLegacyFixtureCannotBecomeRuntimeEvidence() {
        rejects { pr18RequireRuntimeCapture(provenance()) }
    }

    @Test fun sealingRejectsProvenanceFromAnotherObservedCaptureAttempt() {
        val expected = provenance()
        val wrong = expected.copy(source = expected.source.copy(owner = "WRONG-observed-owner"))
        val bytes = realBytes()
        val pixels = realPixels().decodedPixels()
        pr18SealPng(expected.source, expected, pixels, bytes, true, ::decode)
        rejects { pr18SealPng(expected.source, wrong, pixels, bytes, true, ::decode) }
    }

    @Test fun deferredReceiptRejectsChangedReadbackMetadata() {
        val source = provenance()
        val bytes = realBytes()
        val seal = pr18SealPng(source.source, source, realPixels().decodedPixels(), bytes, true, ::decode)
        var metadata = seal.metadata
        val receipt = Pr18CaptureReceipt(listOf(Pr18CaptureRecord(source, seal.encodedSha256) {
            seal.requireReadback(bytes, metadata, source, ::decode)
        }), emptyList())
        receipt.requireValid()
        metadata += "WRONG metadata after behavior gates"
        rejects { receipt.requireValid() }
        rejects { Pr18CaptureReceipt(emptyList(), emptyList()).requireValid() }
    }

    @Test fun encoderOutputMustMatchTheOriginallyCapturedPixels() {
        val image = ImageIO.read(ByteArrayInputStream(realBytes()))
        // Explicit negative capture control: change one palette pixel in memory.
        image.setRGB(0, 0, surface)
        val different = pr18ReadPixels(image.width, image.height, background, surface, ink, foregrounds) { y, row ->
            image.getRGB(0, y, image.width, 1, row, 0, image.width)
        }.decodedPixels()
        assertNotEquals(realPixels().pixelSha256, different.pixelSha256)
        rejects { pr18SealPng(provenance().source, provenance(), different, realBytes(), true, ::decode) }
    }

    @Test fun nullUndecodableAndCompressFailedPngControlsFail() {
        val source = provenance()
        val bytes = realBytes()
        val pixels = realPixels().decodedPixels()
        rejects { pr18SealPng(source.source, source, null, bytes, true, ::decode) }
        rejects { pr18SealPng(source.source, source, pixels, null, true, ::decode) }
        rejects { pr18SealPng(source.source, source, pixels, bytes, false, ::decode) }
        rejects { pr18SealPng(source.source, source, pixels, byteArrayOf(1, 2, 3), true, ::decode) }
        rejects { pr18SealPng(source.source, source, pixels, bytes, true) { null } }
        val seal = pr18SealPng(source.source, source, pixels, bytes, true, ::decode)
        rejects { seal.requireReadback(null, seal.metadata, source, ::decode) }
        rejects { seal.requireReadback(bytes, seal.metadata, source) { null } }
        rejects { seal.requireReadback(bytes, seal.metadata, source) { pixels.copy(width = pixels.width + 1) } }
        rejects { seal.requireReadback(bytes, seal.metadata, source) { pixels.copy(pixelSha256 = "WRONG-decoded-pixels") } }
        // Decoder accepts a trailing byte, but the original encoder never emitted it.
        rejects { seal.requireReadback(bytes + byteArrayOf(0), seal.metadata, source, ::decode) }
    }

    @Test fun nonzeroOwnerOffsetRejectsSaveBottom480BehindImeTop500() {
        val save = pr18WindowToScreen(Pr18Rect(0f, 432f, 48f, 480f), Pr18Point(0f, 100f), Pr18Point(0f, 0f))
        assertFalse("SCREEN bottom 480 + owner Y100 must not clear IME SCREEN top500",
            pr18ClearsIme(save, listOf(Pr18Rect(0f, 500f, 600f, 800f)), 0f))
        assertEquals(580f, save.bottom, 0f)
    }

    @Test fun windowToScreenUsesTheOwnerOffsetRatherThanAddingTheRootOffsetTwice() {
        val target = Pr18Rect(30f, 100f, 78f, 148f)
        assertEquals(Pr18Rect(130f, 300f, 178f, 348f),
            pr18WindowToScreen(target, Pr18Point(120f, 240f), Pr18Point(20f, 40f)))
        assertFalse(pr18ClearsIme(target, emptyList(), 0f))
    }

    @Test fun viewLocalVisibilityUsesTheRealScreenOffset() {
        val good = screenTarget()
        val screen = pr18LocalToScreen(Pr18Rect(0f, 0f, 48f, 48f), Pr18Point(100f, 100f))
        assertEquals("Android local visible rectangle must include the owner SCREEN offset", good.full, screen)
        assertEquals(good.point, pr18RequireScreenTarget(good.copy(visible = screen), emptyList()))
    }

    @Test fun everyImeRectangleMustParticipateInTheScreenGate() {
        val observed = listOf(Pr18Rect(0f, 800f, 100f, 1000f), Pr18Rect(100f, 400f, 600f, 1000f))
        val save = Pr18Rect(120f, 432f, 168f, 480f)
        assertFalse("Second IME rectangle covers the save target", pr18ClearsIme(save, pr18ImeRectangles(observed), 0f))
        assertEquals(observed, pr18ImeRectangles(observed))
    }

    @Test fun viewDispatchedCallbackCannotProvePhysicalScreenDelivery() {
        rejects { pr18RequireScreenTarget(screenTarget().copy(mode = Pr18TouchMode.VIEW_DISPATCH), emptyList()) }
    }

    @Test fun wrongOwnerBoundsHiddenCoveredAndUnavailableTargetsFail() {
        val good = screenTarget()
        assertEquals(good.point, pr18RequireScreenTarget(good, emptyList()))
        for (bad in listOf(good.copy(rootOwner = "WRONG-owner"), good.copy(rootMatchesOwner = false),
            good.copy(observedWindowId = -1), good.copy(accessibilityNodeId = 2),
            good.copy(attached = false), good.copy(shown = false), good.copy(accessibilityVisible = false),
            good.copy(full = Pr18Rect(0f, 0f, 48f, 48f)), good.copy(clipped = Pr18Rect(0f, 0f, 0f, 0f)),
            good.copy(point = Pr18Point(Float.NaN, 124f)), good.copy(displayId = 1))) {
            rejects { pr18RequireScreenTarget(bad, emptyList()) }
        }
        rejects { pr18RequireScreenTarget(good, listOf(Pr18Rect(120f, 120f, 130f, 130f))) }
        rejects { pr18RequireScreenTarget(good, listOf(Pr18Rect(0f, 0f, Float.NaN, 100f))) }
    }

    @Test fun physicalInputNeedsBothAcceptedEventsAndExactCallbackDelta() {
        val target = screenTarget()
        rejects { Pr18TouchReceipt(target, false, true) }
        rejects { Pr18TouchReceipt(target, true, false) }
        rejects { Pr18TouchReceipt(target.copy(mode = Pr18TouchMode.VIEW_DISPATCH), true, true) }
        val receipt = Pr18TouchReceipt(target, true, true)
        receipt.requireCallbackDelta(4, 5)
        rejects { receipt.requireCallbackDelta(4, 4) }
        rejects { receipt.requireCallbackDelta(4, 6) }
    }
}
