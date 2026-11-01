package top.alanvampuke.dininglog.feature

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import java.util.UUID
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.components.*
import top.alanvampuke.dininglog.feature.restaurants.*
import top.alanvampuke.dininglog.feature.revisions.*
import top.alanvampuke.dininglog.feature.settings.*
import top.alanvampuke.dininglog.feature.theme.*

@Serializable data object RestaurantsRoute
@Serializable data object VisitsRoute
@Serializable data object SettingsRoute
@Serializable data class DetailRoute(val kind: String, val id: String, val restaurantId: String? = null)
@Serializable data class EditRoute(val kind: String, val id: String, val restaurantId: String? = null, val create: Boolean = false, val draftId: String? = null)
@Serializable data class NestedListRoute(val kind: String, val restaurantId: String? = null, val deleted: Boolean = false)
@Serializable data class ChooseRestaurantRoute(val purpose: String)

@Composable fun ProductRoot(vm: ProductViewModel, username: String, theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit,
    onLogout: () -> Unit, version: String = "0.4.0 debug") {
    val nav = rememberNavController()
    val list by vm.list.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val editorDishes by vm.editorDishes.collectAsStateWithLifecycle()
    val dishes by vm.relatedDishes.collectAsStateWithLifecycle()
    val visits by vm.relatedVisits.collectAsStateWithLifecycle()
    val apply by vm.apply.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    fun openRecord(record: RecordSummary) { nav.navigate(DetailRoute(record.ref.kind.name, record.ref.id, record.ref.restaurantId)) }
    fun create(kind: RecordKind, parent: String? = null) { nav.navigate(EditRoute(kind.name, UUID.randomUUID().toString(), parent, create = true)) }
    fun back() { nav.popBackStack() }
    val tabs: @Composable () -> Unit = {
        val selected = entry?.destination?.route?.substringBefore('?')
        BottomTabs(selected) { tab ->
            vm.selectTab(tab)
            when (tab) {
                "restaurants" -> nav.navigate(RestaurantsRoute) { popUpTo<RestaurantsRoute>(); launchSingleTop = true }
                "visits" -> nav.navigate(VisitsRoute) { popUpTo<RestaurantsRoute>(); launchSingleTop = true }
                else -> nav.navigate(SettingsRoute) { popUpTo<RestaurantsRoute>(); launchSingleTop = true }
            }
        }
    }
    LaunchedEffect(vm) {
        vm.events.collect { event -> when (event) {
            is ProductEvent.Saved -> if (event.fromEditor) {
                nav.popBackStack()
                nav.navigate(DetailRoute(event.ref.kind.name, event.ref.id, event.ref.restaurantId)) { launchSingleTop = true }
            }
        } }
    }
    NavHost(nav, startDestination = when (vm.restoredTab) { "visits" -> VisitsRoute; "settings" -> SettingsRoute; else -> RestaurantsRoute }) {
        composable<RestaurantsRoute> {
            LaunchedEffect(Unit) { vm.activateList(RecordKind.RESTAURANT) }
            RecordListScreen(list, search, filters, vm::setSearch, vm::setFilters, ::openRecord, vm::refresh, vm::loadMore,
                onAdd = { create(RecordKind.RESTAURANT) }, onTrash = { nav.navigate(NestedListRoute(RecordKind.RESTAURANT.name, deleted = true)) }, bottomBar = tabs)
        }
        composable<VisitsRoute> {
            LaunchedEffect(Unit) { vm.activateList(RecordKind.VISIT, vm.visitRestaurantId) }
            RecordListScreen(list, search, filters, vm::setSearch, vm::setFilters, ::openRecord, vm::refresh, vm::loadMore,
                onAdd = { nav.navigate(ChooseRestaurantRoute("new-visit")) }, onTrash = { nav.navigate(NestedListRoute(RecordKind.VISIT.name, vm.visitRestaurantId, true)) },
                onChooseRestaurant = { nav.navigate(ChooseRestaurantRoute("filter-visits")) },
                onClearRestaurant = if (vm.visitRestaurantId != null) { { vm.filterVisits(null) } } else null, bottomBar = tabs)
        }
        composable<SettingsRoute> {
            LaunchedEffect(Unit) { vm.loadSettings() }
            SettingsScreen(settings, username, drafts, theme, version, vm::loadSettings, vm::revokeSession, vm::changePassword,
                onLogout, onDraft = { draft -> nav.navigate(EditRoute(draft.ref.kind.name, draft.ref.id, draft.ref.restaurantId, draft.action == WriteAction.CREATE, draft.id)) }, onTheme, tabs)
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
                onHistory = { record -> nav.navigate(NestedListRoute(RecordKind.REVISION.name, if (record.ref.kind == RecordKind.RESTAURANT) record.ref.id else record.ref.restaurantId)) },
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
            LaunchedEffect(route) { vm.openEditor(ref, route.create, route.draftId) }
            fun leave(next: () -> Unit) { vm.leaveEditor(next) }
            BackHandler { if (!editor.saving) leave(::back) }
            EditScreen(editor, vm::editField, vm::editFirstVisit, vm::saveEditor, onBack = { if (!editor.saving) leave(::back) },
                onDiscard = { vm.discardEditor(::back) }, dishes = editorDishes,
                onDish = { child -> leave { openRecord(child) } },
                onAddDish = { leave { create(RecordKind.DISH, route.id) } },
                onManageDishes = { leave { nav.navigate(NestedListRoute(RecordKind.DISH.name, route.id)) } })
        }
        composable<NestedListRoute> { destination ->
            val route = destination.toRoute<NestedListRoute>()
            val kind = RecordKind.valueOf(route.kind)
            LaunchedEffect(route) { vm.activateList(kind, route.restaurantId, if (route.deleted) DeletedFilter.ONLY else DeletedFilter.EXCLUDE) }
            RecordListScreen(list, search, filters, vm::setSearch, vm::setFilters, ::openRecord, vm::refresh, vm::loadMore,
                onAdd = if (!route.deleted && kind in setOf(RecordKind.DISH, RecordKind.VISIT) && route.restaurantId != null) { { create(kind, route.restaurantId) } } else null,
                onTrash = if (!route.deleted) { { nav.navigate(route.copy(deleted = true)) } } else null, onBack = ::back, bottomBar = tabs)
        }
        composable<ChooseRestaurantRoute> { destination ->
            val route = destination.toRoute<ChooseRestaurantRoute>()
            LaunchedEffect(route) { vm.activateList(RecordKind.RESTAURANT) }
            RecordListScreen(list, search, filters, vm::setSearch, vm::setFilters, onRecord = { restaurant ->
                if (route.purpose == "new-visit") { nav.popBackStack(); create(RecordKind.VISIT, restaurant.ref.id) }
                else { vm.filterVisits(restaurant.ref.id); nav.popBackStack() }
            }, onRefresh = vm::refresh, onMore = vm::loadMore, onBack = ::back, titleOverride = "选择餐厅", bottomBar = tabs)
        }
    }
    val conflict = editor.conflict
    if (conflict != null) ConflictDialog(conflict, editor.saving, vm::confirmConflict, vm::dismissComparison)
    val expired = editor.expired
    if (expired != null) ExpiredRequestDialog(expired, editor.saving, vm::confirmExpired, vm::acceptServer, vm::dismissComparison)
    if (apply != null) ApplyFieldsDialog(apply!!, vm::applySide, vm::selectApply, vm::submitApply, vm::dismissApply)
}
@Composable fun BottomTabs(selectedRoute: String?, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = LocalQuietGridColors.current.surface, tonalElevation = 0.dp, windowInsets = WindowInsets.navigationBars) {
        listOf(Triple("restaurants", "餐厅", "≡"), Triple("visits", "到店", "◷"), Triple("settings", "我的", "○")).forEach { (id, title, icon) ->
            NavigationBarItem(selected = selectedRoute?.contains(when (id) { "restaurants" -> "RestaurantsRoute"; "visits" -> "VisitsRoute"; else -> "SettingsRoute" }) == true,
                onClick = { onSelect(id) }, icon = { Text(icon) }, label = { Text(title) }, modifier = Modifier.testTag("tab:$id"))
        }
    }
}
