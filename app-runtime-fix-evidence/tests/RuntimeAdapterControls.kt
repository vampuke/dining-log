package top.alanvampuke.dininglog.product

import android.graphics.Rect as AndroidRect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

// These are synthetic observations fed to the *extracted* adapter. They do not
// represent new Android runs, window/font metadata, PNGs or physical callbacks.
class RuntimeAdapterControls {
    private val area = Rect(0f, 0f, 600f, 800f)
    private val targetArea = Rect(200f, 600f, 400f, 700f)

    private class Scene(val visible: Rect = Rect(0f, 0f, 1000f, 1000f), val window: Window = Window()) {
        val rootNode = SemanticsNode(-9000, visible)
        val view = View(window, visible = visible)
        val root = ViewRootForTest(view, SemanticsOwner(rootNode))
        val nodes = mutableListOf(rootNode)
        init { rootNode.root = root }
        fun add(id: Int, full: Rect, parent: SemanticsNode = rootNode, tag: String? = null,
            action: SemanticsPropertyKey<Unit>? = null, clipped: Rect = full,
            outer: Rect = full, outerClipped: Rect = outer, touch: Rect = clipped): SemanticsNode {
            val node = SemanticsNode(id, full, clipped, touch, outer, outerClipped, parent)
            node.root = root
            if (tag != null) node.config[SemanticsProperties.TestTag] = tag
            if (action != null) node.config[action] = Unit
            parent.children += node
            nodes += node
            return node
        }
        fun probe(extra: List<SemanticsNode> = emptyList(), windows: List<AccessibilityWindowInfo> =
            listOf(AccessibilityWindowInfo(window.accessibilityId, 10))) =
            // A query's enumeration order is deliberately the reverse of paint order.
            AdapterProbe(FixtureCompose((nodes + extra).reversed()), windows)
    }

    @Test fun positivePhysicalAreaWithoutCoverIsExposed() {
        val s = Scene()
        val target = s.add(1, targetArea, tag = "CONTROL-target", action = SemanticsActions.OnClick)
        assertTrue(s.probe().exposed(target))
    }

    @Test fun lowerScrollListMayOverlapFloatingTab() {
        val s = Scene()
        s.add(900, area, tag = "record-list", action = SemanticsActions.ScrollBy)
        val target = s.add(1, targetArea, tag = "tab:settings", action = SemanticsActions.OnClick)
        assertTrue("Lower list must not obstruct the later-painted capsule", s.probe().exposed(target))
    }

    @Test fun lowerNestedClickableRowMayOverlapFloatingTab() {
        val s = Scene()
        val body = s.add(800, area)
        val list = s.add(900, area, parent = body, action = SemanticsActions.ScrollBy)
        s.add(1000, targetArea, parent = list, action = SemanticsActions.OnClick)
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        assertTrue("Compare whole paint branches, not semantic IDs or nested depth", s.probe().exposed(target))
    }

    @Test fun lowerUntaggedScrimDoesNotObstructLaterTarget() {
        val s = Scene()
        s.add(900, area, action = SemanticsActions.OnClick)
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        assertTrue(s.probe().exposed(target))
    }

    @Test fun higherNestedScrimWithSmallerIdStillBlocksTarget() {
        val s = Scene()
        val target = s.add(1000, targetArea, action = SemanticsActions.OnClick)
        val overlayBranch = s.add(2, area)
        s.add(1, targetArea, parent = overlayBranch, action = SemanticsActions.OnClick)
        assertFalse(s.probe().exposed(target))
    }

    @Test fun actualInputBoundsDoNotIncludeOuterLayoutPadding() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        s.add(2, Rect(0f, 0f, 600f, 500f), action = SemanticsActions.ScrollBy, outer = area)
        assertTrue("Even a higher scroll surface does not accept a touch in its outer padding", s.probe().exposed(target))
    }

    @Test fun higherMinimumTouchExtensionRemainsAnObstruction() {
        val s = Scene()
        val target = s.add(1, Rect(40f, 40f, 60f, 60f), action = SemanticsActions.OnClick)
        s.add(2, Rect(60f, 40f, 70f, 60f), action = SemanticsActions.OnClick,
            touch = Rect(41f, 26f, 89f, 74f))
        assertFalse("Use pinned touch bounds, retaining the clickable minimum-touch extent", s.probe().exposed(target))
    }

    @Test fun clippedOutHigherSurfaceDoesNotBlock() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        s.add(2, targetArea, action = SemanticsActions.ScrollBy,
            clipped = Rect(0f, 0f, 0f, 0f), outerClipped = Rect(0f, 0f, 0f, 0f))
        assertTrue(s.probe().exposed(target))
    }

    @Test fun partiallyClippedHigherInputUsesItsActualClippedRegion() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        s.add(2, area, action = SemanticsActions.ScrollBy, clipped = Rect(0f, 0f, 600f, 500f))
        assertTrue("Clipping removes the claimed obstruction at the touch point", s.probe().exposed(target))
    }

    @Test fun textMeasurementAndSubstitutionDescendantsAreNotInputCovers() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        val label = s.add(2, targetArea, parent = target)
        for (action in listOf(SemanticsActions.GetTextLayoutResult, SemanticsActions.SetTextSubstitution,
            SemanticsActions.ShowTextSubstitution, SemanticsActions.ClearTextSubstitution)) label.config[action] = Unit
        assertTrue(s.probe().exposed(target))
    }

    @Test fun independentlyActionableDescendantIsNotExempted() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        s.add(2, targetArea, parent = target, action = SemanticsActions.OnClick)
        assertFalse(s.probe().exposed(target))
    }

    @Test fun layoutAncestorsAreNotSiblingCoversForMergedTargets() {
        val s = Scene()
        val scroller = s.add(2, area, action = SemanticsActions.ScrollBy)
        val target = s.add(1, targetArea, parent = scroller, action = SemanticsActions.OnClick)
        // A merged semantic chain skips this scroll node; the real LayoutInfo chain does not.
        target.parent = s.rootNode
        target.mergingEnabled = true
        assertTrue(s.probe().exposed(target))
    }

    @Test fun allInputActionKindsRemainBlockedByHigherSurfaces() {
        for (action in listOf(SemanticsActions.OnClick, SemanticsActions.OnLongClick,
            SemanticsActions.ScrollBy, SemanticsActions.SetText, SemanticsActions.SetProgress)) {
            val s = Scene()
            val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
            s.add(2, targetArea, action = action)
            assertFalse(action.name, s.probe().exposed(target))
        }
    }

    @Test fun higherCoversHaveNoTagOrControlExemptions() {
        for (tag in listOf(null, "record-list", "edit-scroll", "save", "password-save", "tab:settings")) {
            val s = Scene()
            val target = s.add(1, targetArea, tag = tag, action = SemanticsActions.OnClick)
            s.add(2, targetArea, tag = tag, action = SemanticsActions.OnClick)
            assertFalse("Higher overlay tag=$tag", s.probe().exposed(target))
        }
    }

    @Test fun unknownOrderBetweenSameWindowComposeRootsFailsClosed() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        val other = Scene(window = s.window)
        other.add(2, targetArea, action = SemanticsActions.OnClick)
        assertFalse("Different root ranks are incomparable", s.probe(other.nodes).exposed(target))
    }

    @Test fun unavailableTargetPaintRankCannotBecomeAFalseGreen() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        s.rootNode.children.remove(target) // Inconsistent observation: query returned an unranked target.
        s.add(2, targetArea, action = SemanticsActions.OnClick)
        assertFalse(s.probe().exposed(target))
    }

    @Test fun differentOwnersUseObservedWindowLayersForHigherCover() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        val other = Scene(window = Window(9))
        other.add(2, targetArea, action = SemanticsActions.OnClick)
        val windows = listOf(AccessibilityWindowInfo(7, 10), AccessibilityWindowInfo(9, 20,
            rectangles = listOf(AndroidRect(200, 600, 400, 700))))
        assertFalse(s.probe(other.nodes, windows).exposed(target))
    }

    @Test fun differentLowerOwnerDoesNotObstructFocusedUpperWindow() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        val other = Scene(window = Window(9))
        other.add(2, targetArea, action = SemanticsActions.OnClick)
        val windows = listOf(AccessibilityWindowInfo(7, 10), AccessibilityWindowInfo(9, 0,
            rectangles = listOf(AndroidRect(200, 600, 400, 700))))
        assertTrue(s.probe(other.nodes, windows).exposed(target))
    }

    @Test fun unknownOwnerWindowAvailabilityFailsClosed() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        val failure = runCatching { s.probe(windows = emptyList()).exposed(target) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("owner window availability is unknown"))
    }

    @Test fun observedImeRectanglesStillBlockTheTarget() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        s.view.imeVisible = true
        val windows = listOf(AccessibilityWindowInfo(7, 10), AccessibilityWindowInfo(8, 20,
            AccessibilityWindowInfo.TYPE_INPUT_METHOD, listOf(AndroidRect(0, 500, 600, 800))))
        assertFalse(s.probe(windows = windows).exposed(target))
    }

    @Test fun visibleImeWithUnknownGeometryStillRejects() {
        val s = Scene()
        val target = s.add(1, targetArea, action = SemanticsActions.OnClick)
        s.view.imeVisible = true
        val failure = runCatching { s.probe().exposed(target) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("IME visible but its actual screen geometry is unknown"))
    }

    @Test fun all56ActualFailureGeometryControlsAcceptOnlyTheProvenLowerPlane() {
        val controls = observedFailureControls()
        assertEquals(56, controls.size)
        val rejected = mutableListOf<String>()
        for (control in controls) {
            val s = Scene(control.rootVisible)
            val body = s.add(-9002, control.rootVisible)
            for (cover in control.covers) s.add(cover.id, cover.full, parent = body,
                action = SemanticsActions.ScrollBy, clipped = cover.clipped,
                outer = cover.outer, outerClipped = cover.outerClipped)
            val target = s.add(control.targetId, control.target, action = SemanticsActions.OnClick, clipped = control.clipped)
            val exposed = s.probe().exposed(target)
            println("CONTROL actual-CI geometry / audited lower order ${control.name}: exposed=$exposed")
            if (!exposed) rejected += control.name
        }
        assertEquals("All audited lower-plane controls must expose the actual target", emptyList<String>(), rejected)
    }

    private fun progress(state: ProgressState, text: String) {
        val s = Scene()
        val node = s.add(1, targetArea, tag = "update-progress")
        node.config[SemanticsProperties.Text] = listOf(AnnotatedString(text))
        s.probe().progress(state)
    }

    @Test fun knownProgressRequiresTheCompleteStatusAndExactPercentage() {
        val state = ProgressState(UpdateStage.DOWNLOADING, 50)
        assertEquals("正在下载 · 50%", productionProgress(state))
        progress(state, productionProgress(state))
    }

    @Test fun unknownProgressRequiresTheCompleteStatus() {
        val state = ProgressState(UpdateStage.DOWNLOADING, null)
        assertEquals("正在下载 · 百分比待确认", productionProgress(state))
        progress(state, productionProgress(state))
    }

    @Test fun verifyingProgressRequiresTheExactVerificationStatus() {
        val state = ProgressState(UpdateStage.VERIFYING, 100)
        assertEquals("正在校验安装包…", productionProgress(state))
        progress(state, productionProgress(state))
    }

    @Test fun progressCannotPassWithWrongMissingOrTruncatedStatus() {
        val state = ProgressState(UpdateStage.DOWNLOADING, 50)
        for (text in listOf("正在下载 · 49%", "50%", "错误 · 50%", "正在下载 · 50% extra", "")) {
            assertTrue("Wrong status must reject: $text", runCatching { progress(state, text) }.exceptionOrNull() is AssertionError)
        }
    }
}
