package top.alanvampuke.dininglog.feature

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch
import java.util.UUID
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.restaurants.*
import top.alanvampuke.dininglog.feature.revisions.*
import top.alanvampuke.dininglog.feature.settings.*
import top.alanvampuke.dininglog.feature.theme.*

@Serializable data object RestaurantsRoute
@Serializable data object SettingsRoute
@Serializable data class DetailRoute(val kind: String, val id: String, val restaurantId: String? = null)
@Serializable data class EditRoute(val kind: String, val id: String, val restaurantId: String? = null, val create: Boolean = false)
@Serializable data class NestedListRoute(val kind: String, val restaurantId: String? = null, val deleted: Boolean = false,
    val historyEntityKind: String? = null, val historyEntityId: String? = null)

@Composable fun ProductRoot(vm: ProductViewModel, username: String, theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit,
    onLogout: () -> Unit, version: String,
    updateState: AppUpdateState = AppUpdateState(), onUpdateToggle: (Boolean) -> Unit = {}, onOpenUpdate: (AppRelease) -> Unit = {},
    onCheckUpdate: () -> Unit = {}) {
    // New registry key prevents an upgrade from restoring the removed tab destination.
    val nav = key("p8-navigation") { rememberNavController() }
    val animate = rememberNavigationAnimationsEnabled()
    val motionPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val restaurantScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(vm.restoredTab) }
    SideEffect { if (selectedTab !in setOf("restaurants", "settings")) selectedTab = "restaurants" }
    val list by vm.list.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val editorDishes by vm.editorDishes.collectAsStateWithLifecycle()
    val dishes by vm.relatedDishes.collectAsStateWithLifecycle()
    val visits by vm.relatedVisits.collectAsStateWithLifecycle()
    val apply by vm.apply.collectAsStateWithLifecycle()
    val operationVisible by vm.operationVisible.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.setForeground(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.setForeground(false) }
    fun openRecord(record: RecordSummary) { nav.navigate(DetailRoute(record.ref.kind.name, record.ref.id, record.ref.restaurantId)) }
    fun create(kind: RecordKind, parent: String? = null) { nav.navigate(EditRoute(kind.name, UUID.randomUUID().toString(), parent, create = true)) }
    fun back() { nav.popBackStack() }
    val tabs: @Composable () -> Unit = {
        val selected = when {
            entry?.destination?.hasRoute<RestaurantsRoute>() == true -> "restaurants"
            entry?.destination?.hasRoute<SettingsRoute>() == true -> "settings"
            else -> selectedTab
        }
        BottomTabs(selected, onAdd = {
            create(RecordKind.RESTAURANT)
        }) { tab ->
            val destination = nav.currentBackStackEntry?.destination
            val current = when (tab) {
                "restaurants" -> destination?.hasRoute<RestaurantsRoute>() == true
                else -> destination?.hasRoute<SettingsRoute>() == true
            }
            if (current) {
                val scroll = when (tab) { "restaurants" -> restaurantScroll; else -> settingsScroll }
                scope.launch { if (animate) scroll.animateScrollToItem(0) else scroll.scrollToItem(0) }
                return@BottomTabs
            }
            selectedTab = tab
            vm.selectTab(tab)
            when (tab) {
                "restaurants" -> nav.navigate(RestaurantsRoute) { popUpTo(nav.graph.findStartDestination().id); launchSingleTop = true }
                else -> nav.navigate(SettingsRoute) { popUpTo(nav.graph.findStartDestination().id); launchSingleTop = true }
            }
        }
    }
    LaunchedEffect(vm) {
        vm.events.collect { event -> when (event) {
            is ProductEvent.Saved -> if (event.fromEditor) {
                nav.popBackStack()
                val target = DetailRoute(event.ref.kind.name, event.ref.id, event.ref.restaurantId)
                val current = nav.currentBackStackEntry?.takeIf { it.destination.hasRoute<DetailRoute>() }?.toRoute<DetailRoute>()
                // Restaurant and child details share a destination type. Only
                // reuse the entry when its actual record IDs also match.
                if (current != target) nav.navigate(target)
            }
        } }
    }
    val colors = LocalQuietGridColors.current
    NavHost(nav, startDestination = when (vm.restoredTab) { "settings" -> SettingsRoute; else -> RestaurantsRoute },
        modifier = Modifier.fillMaxSize().background(colors.bg).testTag("product-navigation").semantics {
            this[BackgroundArgb] = colors.bg.toArgb(); this[NavigationAnimationsEnabled] = animate
            entry?.id?.let { this[NavigationEntryId] = it }
        },
        enterTransition = { if (animate) fadeIn(tween(200)) + slideInVertically(tween(200)) { motionPx } else EnterTransition.None },
        exitTransition = { if (animate) fadeOut(tween(180)) + slideOutVertically(tween(180)) { -motionPx } else ExitTransition.None },
        popEnterTransition = { if (animate) fadeIn(tween(200)) + slideInVertically(tween(200)) { -motionPx } else EnterTransition.None },
        popExitTransition = { if (animate) fadeOut(tween(180)) + slideOutVertically(tween(180)) { motionPx } else ExitTransition.None }) {
        composable<RestaurantsRoute> {
            LaunchedEffect(Unit) { vm.activateList(RecordKind.RESTAURANT) }
            RecordListScreen(list, search, filters, vm::setSearch, vm::setFilters, ::openRecord, vm::refresh, vm::loadMore,
                onAdd = { create(RecordKind.RESTAURANT) }, onTrash = { nav.navigate(NestedListRoute(RecordKind.RESTAURANT.name, deleted = true)) }, bottomBar = tabs, listState = restaurantScroll)
        }
        composable<SettingsRoute> {
            LaunchedEffect(Unit) { vm.loadSettings() }
            SettingsScreen(settings, username, theme, version, vm::loadSettings, vm::revokeSession, vm::changePassword,
                onLogout, onTheme, tabs,
                onPasswordInput = vm::editPassword, listState = settingsScroll,
                updateState = updateState, onUpdateToggle = onUpdateToggle, onOpenUpdate = onOpenUpdate, onCheckUpdate = onCheckUpdate)
        }
        composable<DetailRoute> { destination ->
            val route = destination.toRoute<DetailRoute>()
            val kind = RecordKind.valueOf(route.kind)
            val ref = RecordRef(kind, route.id, route.restaurantId)
            LaunchedEffect(ref) { vm.openDetail(ref) }
            if (kind == RecordKind.REVISION) RevisionDetailScreen(detail, ::back, vm::refreshDetail,
                onNote = { nav.navigate(EditRoute(kind.name, route.id, route.restaurantId)) },
                onHide = { vm.mutate(it, WriteAction.DELETE) }, onRestore = { vm.mutate(it, WriteAction.RESTORE) }, onApply = vm::previewApply, bottomBar = tabs)
            else RecordDetailScreen(detail, ::back, vm::refreshDetail,
                onEdit = { nav.navigate(EditRoute(kind.name, route.id, route.restaurantId)) }, onDelete = { vm.mutate(it, WriteAction.DELETE) }, onRestore = { vm.mutate(it, WriteAction.RESTORE) },
                onHistory = { record -> nav.navigate(NestedListRoute(RecordKind.REVISION.name,
                    if (record.ref.kind == RecordKind.RESTAURANT) record.ref.id else record.ref.restaurantId,
                    historyEntityKind = if (record.ref.kind == RecordKind.RESTAURANT) null else record.ref.kind.name,
                    historyEntityId = if (record.ref.kind == RecordKind.RESTAURANT) null else record.ref.id)) },
                dishes = dishes, visits = visits, onChild = ::openRecord,
                onDishes = if (kind == RecordKind.RESTAURANT) { { nav.navigate(NestedListRoute(RecordKind.DISH.name, route.id)) } } else null,
                onVisits = if (kind == RecordKind.RESTAURANT) { { nav.navigate(NestedListRoute(RecordKind.VISIT.name, route.id)) } } else null,
                onAddDish = if (kind == RecordKind.RESTAURANT) { { create(RecordKind.DISH, route.id) } } else null,
                onNewVisit = if (kind == RecordKind.RESTAURANT) { { create(RecordKind.VISIT, route.id) } } else null, bottomBar = tabs)
        }
        composable<EditRoute> { destination ->
            val route = destination.toRoute<EditRoute>()
            val kind = RecordKind.valueOf(route.kind)
            val ref = RecordRef(kind, route.id, route.restaurantId)
            LaunchedEffect(route) { vm.openEditor(ref, route.create) }
            fun leave(next: () -> Unit) { vm.leaveEditor(next) }
            BackHandler { if (!editor.saving) leave(::back) }
            EditScreen(editor, vm::editField, vm::editFirstVisit, vm::saveEditor, onBack = { if (!editor.saving) leave(::back) },
                dishes = editorDishes,
                onDish = { child -> leave { openRecord(child) } },
                onAddDish = { leave { create(RecordKind.DISH, route.id) } },
                onManageDishes = { leave { nav.navigate(NestedListRoute(RecordKind.DISH.name, route.id)) } })
        }
        composable<NestedListRoute> { destination ->
            val route = destination.toRoute<NestedListRoute>()
            val kind = RecordKind.valueOf(route.kind)
            LaunchedEffect(route) { vm.activateList(kind, route.restaurantId, if (route.deleted) DeletedFilter.ONLY else DeletedFilter.EXCLUDE,
                route.historyEntityKind?.let { RecordKind.valueOf(it) }, route.historyEntityId) }
            RecordListScreen(list, search, filters, vm::setSearch, vm::setFilters, ::openRecord, vm::refresh, vm::loadMore,
                onAdd = if (!route.deleted && kind in setOf(RecordKind.DISH, RecordKind.VISIT) && route.restaurantId != null) { { create(kind, route.restaurantId) } } else null,
                onTrash = if (!route.deleted) { { nav.navigate(route.copy(deleted = true)) } } else null, onBack = ::back, bottomBar = tabs)
        }

    }
    val conflict = editor.conflict
    if (conflict != null) ConflictDialog(conflict, editor.saving, vm::confirmConflict, vm::dismissComparison)
    if (apply != null) ApplyFieldsDialog(apply!!, vm::applySide, vm::selectApply, vm::submitApply, vm::dismissApply)
    if (operationVisible && conflict == null) OperationDialog(editor, vm::saveEditor, vm::dismissOperation)
}
val TabIconFilled = SemanticsPropertyKey<Boolean>("TabIconFilled")
@Composable fun BottomTabs(selectedRoute: String?, onAdd: (() -> Unit)? = null, onSelect: (String) -> Unit) {
    val c = LocalQuietGridColors.current
    Row(Modifier.fillMaxWidth().brutalBox(c.surface, c.border, c.shadow, radius = 28.dp)
        .heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 6.dp).testTag("floating-tabs"),
        verticalAlignment = Alignment.CenterVertically) {
        listOf("restaurants" to "餐厅", "settings" to "我的").forEachIndexed { index, (id, title) ->
            if (index == 1) Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                BiteButton(onClick = { onAdd?.invoke() }, enabled = onAdd != null,
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp).testTag("nav-add").semantics { contentDescription = "新增餐厅" },
                    colors = ButtonDefaults.buttonColors(containerColor = c.cta, contentColor = c.onAccent)) {
                    Text("+", style = MaterialTheme.typography.headlineSmall)
                }
            }
            val selected = selectedRoute == id || selectedRoute?.contains(if (id == "restaurants") "RestaurantsRoute" else "SettingsRoute") == true
            val foreground = if (selected) c.accentInk else c.secondary
            val icon = if (id == "restaurants") {
                if (selected) QuietGridIcons.restaurantFilled else QuietGridIcons.restaurantOutline
            } else if (selected) QuietGridIcons.personFilled else QuietGridIcons.personOutline
            // Intrinsic vertical size grows with font scale; labels have no fixed-height slot.
            Surface(onClick = { onSelect(id) }, color = if (selected) c.accentSoft else c.surface,
                shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).testTag("tab:$id").semantics {
                    this.selected = selected; role = androidx.compose.ui.semantics.Role.Tab
                }) {
                Column(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(24.dp).testTag("tab-icon:$id").semantics {
                        this[TabIconFilled] = selected; this[ForegroundArgb] = foreground.toArgb()
                        this[BackgroundArgb] = (if (selected) c.accentSoft else c.surface).toArgb()
                    })
                    Text(title, Modifier.fillMaxWidth().semantics { this[ForegroundArgb] = foreground.toArgb() },
                        color = foreground, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
