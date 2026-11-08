package top.alanvampuke.dininglog.product

import android.graphics.Rect as AndroidRect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.core.ThemeStyle

// Explicit physical-input CONTROL scenes. They produce geometry/callback logs,
// never screenshots. Every negative starts with a successful real screen tap.
@RunWith(AndroidJUnit4::class)
class Pr18PhysicalTouchBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<P9VisualActivity>()
    private lateinit var evidence: Pr18Evidence
    private val callbacks = AtomicInteger()
    private val targetTag = "CONTROL-physical-target"

    @Before fun prepare() {
        evidence = Pr18Evidence(compose, ThemeStyle.INK_PAPER, false)
        evidence.configure(1f)
        evidence.portrait()
    }
    @After fun restore() { evidence.close() }

    @Composable private fun scene(hidden: Boolean = false, covered: Boolean = false, coverCallbacks: AtomicInteger? = null) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.Center).size(80.dp).alpha(if (hidden) 0f else 1f)
                .testTag(targetTag).clickable { callbacks.incrementAndGet() }.background(Color.Blue),
                contentAlignment = Alignment.Center) { Text("CONTROL", color = Color.White) }
            if (covered) {
                // No TestTag: this is the old whitelist's Compose blind spot.
                Box(Modifier.align(Alignment.Center).size(80.dp).background(Color.Magenta)
                    .clickable { coverCallbacks!!.incrementAndGet() })
            }
        }
    }

    private fun positive(): Pr18TouchReceipt {
        evidence.minimumTarget(targetTag)
        val before = callbacks.get()
        val receipt = evidence.touch(evidence.target(targetTag), "CONTROL-positive-screen-tap")
        assertEquals("Real physical positive must deliver exactly once", before + 1, callbacks.get())
        receipt.requireCallbackDelta(before, callbacks.get())
        return receipt
    }

    private fun rejects(reasons: List<String>, block: () -> Unit): Throwable {
        val before = callbacks.get()
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull("Physical negative CONTROL must reject", failure)
        val message = failure!!.message.orEmpty()
        assertTrue("Must reject the controlled geometry/occlusion/callback error, not unrelated automation availability: $failure",
            reasons.any { message.contains(it) })
        assertEquals("Blocked CONTROL target callback delta remains zero", before, callbacks.get())
        return failure
    }

    private fun coveredTap() = rejects(listOf("occluded by ", "Screen target is occluded",
        "Screen target is hidden, clipped or covered", "Physical target callback delta=0, expected=1")) {
        val before = callbacks.get()
        evidence.touch(evidence.target(targetTag), "CONTROL-covered-screen-tap").requireCallbackDelta(before, callbacks.get())
    }

    @Test fun offsetRealDialogUsesLocalVisibilityAndScreenInput() {
        evidence.render { Dialog(onDismissRequest = {}) {
            Box(Modifier.size(240.dp).background(Color.White)) { scene() }
        } }
        val node = evidence.target(targetTag).fetchSemanticsNode()
        val offset = compose.runOnIdle { IntArray(2).also { (node.root as ViewRootForTest).view.getLocationOnScreen(it) } }
        assertTrue("CONTROL uses a real nonzero Dialog SCREEN offset", offset[0] != 0 || offset[1] != 0)
        assertNotEquals("CONTROL target belongs to the independent Dialog",
            System.identityHashCode(compose.activity.window).toString(), positive().target.owner)
    }

    @Test fun liveWrongOwnerAndBoundsControlsRejectBeforePhysicalInput() {
        evidence.render { scene() }
        val observed = positive().target
        rejects(listOf("owner/root")) { pr18RequireScreenTarget(observed.copy(rootOwner = "WRONG-owner"), emptyList()) }
        rejects(listOf("owner/provenance")) { pr18RequireScreenTarget(observed.copy(observedWindowId = -1), emptyList()) }
        rejects(listOf("bounds/provenance")) {
            pr18RequireScreenTarget(observed.copy(full = observed.full.translate(Pr18Point(0f, 100f))), emptyList())
        }
        rejects(listOf("bounds/provenance")) {
            pr18RequireScreenTarget(observed.copy(clipped = Pr18Rect(0f, 0f, 0f, 0f)), emptyList())
        }
        rejects(listOf("View dispatch")) { pr18RequireScreenTarget(observed.copy(mode = Pr18TouchMode.VIEW_DISPATCH), emptyList()) }
        rejects(listOf("hidden/clipped")) {
            evidence.touch(evidence.target(targetTag), "CONTROL-outside-real-bounds", point = { Offset(-1000f, -1000f) })
        }
    }

    @Test fun hiddenRealTargetCannotUseAViewDispatchedCallbackAsProof() {
        var hidden by mutableStateOf(false)
        evidence.render { scene(hidden = hidden) }
        positive()
        compose.runOnIdle { hidden = true }
        compose.waitForIdle()
        rejects(listOf("Screen target is hidden, clipped or covered", "hidden/clipped target")) {
            evidence.touch(evidence.target(targetTag), "CONTROL-hidden-screen-tap")
        }
    }

    @Test fun untaggedActionableComposeCoverBlocksTheRealScreenTarget() {
        var covered by mutableStateOf(false)
        val coverCallbacks = AtomicInteger()
        evidence.render { scene(covered = covered, coverCallbacks = coverCallbacks) }
        positive()
        compose.runOnIdle { covered = true }
        compose.waitForIdle()
        coveredTap()
        assertEquals("Detected Compose cover rejects before injection", 0, coverCallbacks.get())
    }

    private fun targetPixels(): AndroidRect {
        val bounds = evidence.screenBounds(targetTag)
        // Outward rounding follows the real pixel extent, without extra padding.
        return AndroidRect(floor(bounds.left).toInt(), floor(bounds.top).toInt(), ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt())
    }

    private fun nativeCover(counter: AtomicInteger) = compose.runOnIdle { View(compose.activity).apply {
        setBackgroundColor(android.graphics.Color.MAGENTA)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        // No OnClick and no Compose semantics; only real native event handling.
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) counter.incrementAndGet()
            true
        }
    } }

    private fun requireCoverCallback(rejection: Throwable, counter: AtomicInteger) {
        // A geometric rejection precedes injection; a callback-delta rejection
        // must be backed by the actual event received by the native cover.
        assertEquals("Exact native cover delivery for the observed rejection",
            if (rejection.message.orEmpty().contains("callback delta=0")) 1 else 0, counter.get())
    }

    private fun requireCoverGeometry(cover: View, target: AndroidRect) {
        compose.runOnIdle {
            val actual = AndroidRect()
            assertTrue("Native CONTROL is actually attached and visible", cover.isAttachedToWindow && cover.getLocalVisibleRect(actual))
            val origin = IntArray(2).also { cover.getLocationOnScreen(it) }
            actual.offset(origin[0], origin[1])
            assertTrue("Observed native SCREEN cover contains the entire target", actual.contains(target))
            assertTrue("Native cover contains the physical target center", actual.contains(target.centerX(), target.centerY()))
        }
    }

    @Test fun nativeSameWindowOnTouchListenerCoverCannotDeliverTheTargetCallback() {
        evidence.render { scene() }
        positive()
        val target = targetPixels()
        val coverCallbacks = AtomicInteger()
        val cover = nativeCover(coverCallbacks)
        val decor = compose.activity.window.decorView as ViewGroup
        try {
            compose.runOnIdle {
                val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
                decor.addView(cover, FrameLayout.LayoutParams(target.width(), target.height()).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    leftMargin = target.left - origin[0]; topMargin = target.top - origin[1]
                })
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val origin = IntArray(2).also { cover.getLocationOnScreen(it) }
                val layout = cover.layoutParams as FrameLayout.LayoutParams
                layout.leftMargin += target.left - origin[0]; layout.topMargin += target.top - origin[1]
                cover.layoutParams = layout
            }
            compose.waitForIdle()
            requireCoverGeometry(cover, target)
            requireCoverCallback(coveredTap(), coverCallbacks)
        } finally {
            compose.runOnIdle { decor.removeView(cover) }
            compose.waitForIdle()
        }
    }

    @Test fun nonfocusablePopupWindowCoverCannotDeliverTheTargetCallback() {
        evidence.render { scene() }
        positive()
        val target = targetPixels()
        val coverCallbacks = AtomicInteger()
        val cover = nativeCover(coverCallbacks)
        val popup = compose.runOnIdle { PopupWindow(cover, target.width(), target.height(), false).apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.MAGENTA))
            isTouchable = true
        } }
        try {
            compose.runOnIdle { popup.showAtLocation(compose.activity.window.decorView, Gravity.TOP or Gravity.LEFT, target.left, target.top) }
            compose.waitForIdle()
            compose.runOnIdle {
                val origin = IntArray(2).also { cover.getLocationOnScreen(it) }
                popup.update(target.left + target.left - origin[0], target.top + target.top - origin[1], target.width(), target.height())
            }
            compose.waitForIdle()
            assertFalse("CONTROL popup preserves the host's focus", popup.isFocusable)
            assertTrue(compose.runOnIdle { compose.activity.window.decorView.hasWindowFocus() })
            requireCoverGeometry(cover, target)
            requireCoverCallback(coveredTap(), coverCallbacks)
        } finally {
            compose.runOnIdle { popup.dismiss() }
            compose.waitForIdle()
        }
    }
}
