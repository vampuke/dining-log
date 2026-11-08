package top.alanvampuke.dininglog.product

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import top.alanvampuke.dininglog.P9VisualActivity
import top.alanvampuke.dininglog.feature.ProductViewModel
import top.alanvampuke.dininglog.feature.components.NavigationEntryId

/** Gate A only. Reads existing public objects; never requests a VM or a save. */
internal class Pr18UiCausalProbe private constructor(private val evidence: Pr18Evidence) :
    Application.ActivityLifecycleCallbacks, AutoCloseable {
    companion object {
        private const val PREFIX = "PR18_GATE_A"
        private const val KIND = "DIAGNOSTIC_MODIFIED_INSTRUMENTATION"
        private const val METHOD = "Pr18ScreenAcceptanceTest.f7RestaurantSearchDishVisitHistoryDetailEditAndSettingsCompleteFlowsAt100And200Percent"
        private const val REGISTRY_KEY = "androidx.lifecycle.BundlableSavedStateRegistry.key"
        private const val PROVIDER_KEY = "androidx.lifecycle.internal.SavedStateHandlesProvider"
        private const val HANDLES_KEY = "androidx.lifecycle.internal.SavedStateHandlesVM"

        fun openIfSelected(evidence: Pr18Evidence): Pr18UiCausalProbe? {
            val method = "top.alanvampuke.dininglog.product." + METHOD.replace('.', '#')
            val selector = listOf("INK_PAPER-dark=false", "INK_PAPER-dark=true",
                "QUIET_GRID-dark=false", "QUIET_GRID-dark=true").joinToString(",") { "$method[$it]" }
            if (InstrumentationRegistry.getArguments().getString("class") != selector) return null
            return Pr18UiCausalProbe(evidence).also { it.open() }
        }
    }

    private val application = evidence.compose.activity.application
    private val file = File(application.getExternalFilesDir("p4-screenshots"),
        "$METHOD/pr18-gateA-case${evidence.uiCausalProbeCase}-api${Build.VERSION.SDK_INT}.jsonl")
    private var sequence = 0L
    private var registered = false
    private var outgoing: P9VisualActivity? = null
    private var naturalState: Bundle? = null
    private var outgoingKey: String? = null
    private var saveCallbacks = 0
    private var currentRuntime: Pr18Runtime? = null
    private var expectedVmId: Int? = null
    private val observedBoundaryVms = mutableSetOf<Int>()

    private fun open() = observe("open") {
        application.registerActivityLifecycleCallbacks(this)
        registered = true
        emit("probe-open", mapOf("callbacksRegistered" to registered))
    }

    private fun identity(value: Any?) = mapOf("present" to (value != null),
        "id" to value?.let { System.identityHashCode(it) }, "type" to value?.javaClass?.name)

    @Synchronized
    private fun emit(stage: String, values: Map<String, Any?>) {
        val receipt = mapOf("prefix" to PREFIX, "evidenceKind" to KIND,
            "caseId" to evidence.uiCausalProbeCase, "style" to evidence.style.name, "dark" to evidence.dark,
            "sequence" to ++sequence, "elapsedRealtimeNanos" to SystemClock.elapsedRealtimeNanos(),
            "api" to Build.VERSION.SDK_INT, "requestedFont" to evidence.requestedFont, "stage" to stage) + values
        val line = JSONObject(receipt).toString()
        file.parentFile!!.mkdirs()
        file.appendText("$line\n")
        Log.i("DiningGateA", "$PREFIX $line")
    }

    private fun observe(stage: String, block: () -> Unit) {
        try { block() } catch (error: Exception) {
            // Diagnostic absence is a proof gap, never a replacement assertion.
            try { emit("observation-error", mapOf("attemptedStage" to stage, "errorType" to error.javaClass.name)) }
            catch (_: Exception) { Log.e("DiningGateA", "$PREFIX observation unavailable stage=$stage") }
        }
    }

    private fun productKey(next: Pr18Runtime): String? =
        if (next.account.session.value?.userId == "pr18-owner") "product:pr18-owner:${next.account.generation}" else null

    private fun callbacks(next: Pr18Runtime?) = if (next == null) null else mapOf(
        "runtime" to identity(next), "account" to identity(next.account),
        "accountScope" to identity(next.account.ownerScope), "repository" to identity(next.repository),
        "generation" to next.account.generation, "allowlistedUser" to (next.account.session.value?.userId == "pr18-owner"),
        "detailRequests" to next.repository.detailRequests.map { mapOf("kind" to it.kind.name,
            "id" to it.id, "restaurantId" to it.restaurantId) }, "writes" to next.repository.writes.size,
        "refreshes" to next.repository.refreshes.get(), "logins" to next.auth.logins.get(),
        "themeWrites" to next.themeWrites.get(), "styleWrites" to next.styleWrites.get())

    private fun nativeView(view: View, activity: P9VisualActivity): Map<String, Any?> {
        val insets = ViewCompat.getRootWindowInsets(view)
        val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())
        val surface = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
        return mapOf("view" to identity(view), "rootView" to identity(view.rootView),
            "windowToken" to identity(view.windowToken), "applicationWindowToken" to identity(view.applicationWindowToken),
            "attached" to view.isAttachedToWindow, "shown" to view.isShown, "focused" to view.hasWindowFocus(),
            "matchesActivityRoot" to (view.rootView === activity.window.decorView.rootView),
            "matchesActivityToken" to (view.windowToken != null && view.windowToken === activity.window.decorView.windowToken),
            "width" to view.width, "height" to view.height,
            "imeVisible" to insets?.isVisible(WindowInsetsCompat.Type.ime()),
            "imeInsets" to ime?.let { listOf(it.left, it.top, it.right, it.bottom) },
            "surfaceInsets" to surface?.let { listOf(it.left, it.top, it.right, it.bottom) })
    }

    private fun owner(activity: P9VisualActivity, key: String?): Map<String, Any?> {
        val store = activity.viewModelStore
        val keyed = key?.let { store.get(it) }
        val vm = keyed as? ProductViewModel
        return mapOf("activity" to identity(activity), "window" to identity(activity.window),
            "registry" to identity(activity.savedStateRegistry), "store" to identity(store),
            "provider" to identity(activity.savedStateRegistry.getSavedStateProvider(PROVIDER_KEY)),
            "internalVm" to identity(store.get(HANDLES_KEY)), "productKey" to key, "productVm" to identity(keyed),
            "isProductViewModel" to (vm != null),
            "restoredTab" to vm?.restoredTab, "resourcesFont" to activity.resources.configuration.fontScale,
            "density" to activity.resources.displayMetrics.density, "lifecycle" to activity.lifecycle.currentState.name,
            "secure" to (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0),
            "decor" to nativeView(activity.window.decorView, activity))
    }

    private fun snapshot(stage: String, next: Pr18Runtime? = currentRuntime,
        activity: P9VisualActivity? = null, key: String? = next?.let(::productKey)) = observe(stage) {
        val navigation = evidence.compose.onAllNodesWithTag("product-navigation", true).fetchSemanticsNodes()
        val screens = evidence.compose.onAllNodes(SemanticsMatcher("Gate A allowlisted destination tags") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("screen:")
        }, true).fetchSemanticsNodes()
        val roots = evidence.compose.onAllNodes(isRoot(), true).fetchSemanticsNodes()
        evidence.compose.runOnIdle {
            val actual = activity ?: evidence.compose.activity
            emit(stage, mapOf("owner" to owner(actual, key), "callbacks" to callbacks(next),
                "navigation" to navigation.map { mapOf("nodeId" to it.id,
                    "entryId" to it.config.getOrElse(NavigationEntryId) { "missing-entry" }) },
                "screens" to screens.map { mapOf("nodeId" to it.id, "tag" to it.config[SemanticsProperties.TestTag]) },
                "roots" to roots.map { mapOf("nodeId" to it.id,
                    "native" to nativeView((it.root as ViewRootForTest).view, actual)) },
                "expectedVmId" to expectedVmId,
                "sameVmAsHelper" to (key?.let { actual.viewModelStore.get(it) }?.let { System.identityHashCode(it) } == expectedVmId)))
        }
    }

    fun terminal(next: Pr18Runtime) = observe("terminal-font100") {
        evidence.compose.runOnIdle { outgoing = evidence.compose.activity; outgoingKey = productKey(next) }
        snapshot("terminal-font100", next, outgoing, outgoingKey)
        currentRuntime = null
    }

    fun disposedBeforeClear() = snapshot("disposed-before-clear", activity = outgoing, key = outgoingKey)
    fun afterClear() = snapshot("disposed-after-clear", activity = outgoing, key = outgoingKey)

    // The lifecycle callback precedes ComponentActivity.performSave. Read only
    // here, after the original configure() returned and the composition idled.
    @Suppress("DEPRECATION")
    fun configurationComplete() {
        try { observe("configuration-returned") {
            evidence.compose.runOnIdle {
                val incoming = evidence.compose.activity
                emit("configuration-returned", mapOf("owner" to owner(incoming, outgoingKey),
                    "sameActivityAsOutgoing" to (incoming === outgoing)))
                if (outgoing != null) {
                    val registryValue = naturalState?.get(REGISTRY_KEY)
                    val registry = registryValue as? Bundle
                    val providerValue = registry?.get(PROVIDER_KEY)
                    val provider = providerValue as? Bundle
                    val productValue = outgoingKey?.let { provider?.get(it) }
                    val product = productValue as? Bundle
                    val tab = product?.get("tab")
                    emit("natural-save-complete", mapOf("outgoingActivity" to identity(outgoing),
                        "saveCallbacks" to saveCallbacks, "outState" to identity(naturalState), "productKey" to outgoingKey,
                        "registryFieldPresent" to (naturalState?.containsKey(REGISTRY_KEY) == true), "registryField" to identity(registryValue),
                        "providerFieldPresent" to (registry?.containsKey(PROVIDER_KEY) == true), "providerField" to identity(providerValue),
                        "productFieldPresent" to (outgoingKey?.let { provider?.containsKey(it) } == true), "productField" to identity(productValue),
                        "tabPresent" to (product?.containsKey("tab") == true), "tabType" to tab?.javaClass?.name,
                        "tab" to if (tab == "settings" || tab == "restaurants") tab else null,
                        "unexpectedTabRedacted" to (tab != null && tab != "settings" && tab != "restaurants"),
                        "readAfterConfigurationReturnedAndIdle" to true))
                }
            }
        } } finally { outgoing = null; naturalState = null; outgoingKey = null }
    }

    fun beforeRender(next: Pr18Runtime) {
        currentRuntime = next; expectedVmId = null
        snapshot("before-production-render", next)
    }

    fun afterRender(next: Pr18Runtime, vm: ProductViewModel?) {
        expectedVmId = vm?.let { System.identityHashCode(it) }
        snapshot("after-production-render", next)
    }

    /** Owner-boundary observation, not access to ProductRoot's private nav. */
    fun compositionBoundary(next: Pr18Runtime, localOwner: ViewModelStoreOwner?,
        registryOwner: SavedStateRegistryOwner, extras: CreationExtras?, view: View,
        compositionFont: Float, compositionDensity: Float) = observe("composition-owner-boundary") {
        val activity = evidence.compose.activity
        val key = productKey(next)
        val vm = key?.let { localOwner?.viewModelStore?.get(it) } as? ProductViewModel
        val vmId = vm?.let { System.identityHashCode(it) }
        if (vmId != null && !observedBoundaryVms.add(vmId)) return@observe
        emit("composition-owner-boundary", mapOf("owner" to owner(activity, key), "callbacks" to callbacks(next),
            "localVmOwner" to identity(localOwner), "localRegistryOwner" to identity(registryOwner),
            "localStore" to identity(localOwner?.viewModelStore), "defaultExtras" to identity(extras),
            "extrasVmOwner" to identity(extras?.get(VIEW_MODEL_STORE_OWNER_KEY)),
            "extrasRegistryOwner" to identity(extras?.get(SAVED_STATE_REGISTRY_OWNER_KEY)),
            "localOwnerMatchesActivity" to (localOwner === activity), "localRegistryMatchesActivity" to (registryOwner === activity),
            "extrasVmOwnerMatchesLocal" to (extras?.get(VIEW_MODEL_STORE_OWNER_KEY) === localOwner),
            "extrasRegistryOwnerMatchesLocal" to (extras?.get(SAVED_STATE_REGISTRY_OWNER_KEY) === registryOwner),
            "boundaryVm" to identity(vm), "sameVmAsActivity" to (vm != null && vm === key?.let { activity.viewModelStore.get(it) }),
            "initialRestoredTab" to vm?.restoredTab, "firstExistingVmObservation" to (vm != null),
            "compositionFont" to compositionFont, "compositionDensity" to compositionDensity,
            "compositionView" to nativeView(view, activity)))
    }

    fun failure(stage: String) = snapshot("failure-$stage")

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        if (activity !== outgoing) return
        naturalState = outState; saveCallbacks++
        observe("natural-save-callback") { emit("natural-save-callback", mapOf(
            "outgoingActivity" to identity(activity), "outState" to identity(outState), "contentsRead" to false,
            "resourcesFont" to activity.resources.configuration.fontScale,
            "lifecycle" to (activity as P9VisualActivity).lifecycle.currentState.name)) }
    }

    private fun lifecycle(stage: String, activity: Activity) {
        if (activity === outgoing) observe(stage) { emit(stage, mapOf("outgoingActivity" to identity(activity))) }
    }
    override fun onActivityPaused(activity: Activity) = lifecycle("outgoing-paused", activity)
    override fun onActivityStopped(activity: Activity) = lifecycle("outgoing-stopped", activity)
    override fun onActivityDestroyed(activity: Activity) = lifecycle("outgoing-destroyed", activity)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit

    override fun close() {
        try { if (registered) { application.unregisterActivityLifecycleCallbacks(this); registered = false } }
        finally {
            outgoing = null; naturalState = null; outgoingKey = null; currentRuntime = null
            observe("probe-closed") { emit("probe-closed", mapOf("callbacksRegistered" to registered,
                "retainedActivity" to (outgoing != null), "retainedBundle" to (naturalState != null),
                "retainedRuntime" to (currentRuntime != null))) }
        }
    }
}
