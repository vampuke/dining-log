package top.alanvampuke.dininglog.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.util.UUID
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*

data class DetailUiState(val cached: CachedDetail = CachedDetail(), val refreshing: Boolean = false,
    val offline: Boolean = false, val error: String? = null)
data class EditorUiState(val input: SaveInput? = null, val loading: Boolean = false, val saving: Boolean = false,
    val message: String? = null, val errors: Map<String, String> = emptyMap(),
    val conflict: ConflictComparison? = null)
data class PasswordInputs(val current: String = "", val replacement: String = "", val repeated: String = "")
data class SettingsUiState(val profile: AccountInfo? = null, val sessions: List<DeviceSession> = emptyList(),
    val loading: Boolean = false, val busy: Boolean = false, val message: String? = null,
    val passwordUnknown: Boolean = false, val passwordRevision: Int = 0, val passwordInputs: PasswordInputs = PasswordInputs())
data class ApplyUiState(val source: RecordSnapshot, val current: RecordSnapshot, val side: String = "before", val selected: Set<String> = emptySet()) {
    val eligible: List<Change> get() = source.diff.filter { it.field in editableFields(current.ref.kind) }
    fun proposed(change: Change) = if (side == "before") change.before else change.after
}
sealed interface ProductEvent {
    data class Saved(val ref: RecordRef, val fromEditor: Boolean) : ProductEvent
}
private data class LoadFlags(val refreshing: Boolean = false, val more: Boolean = false, val offline: Boolean = false, val error: String? = null)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ProductViewModel(val accountId: String, private val repository: ProductRepository,
    private val security: AccountSecurity, account: AccountSession, private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val job = SupervisorJob(account.ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val _search = MutableStateFlow("")
    val search = _search.asStateFlow()
    private val _query = MutableStateFlow(ListQuery())
    private val _filters = MutableStateFlow(ListFilters())
    val filters = _filters.asStateFlow()
    private val _list = MutableStateFlow(ListUiState())
    val list = _list.asStateFlow()
    private val _detailRef = MutableStateFlow<RecordRef?>(null)
    private val _detail = MutableStateFlow(DetailUiState())
    val detail = _detail.asStateFlow()
    private val _editor = MutableStateFlow(EditorUiState())
    private var editorKey: Pair<RecordRef, Boolean>? = null
    private var editorLoadJob: Job? = null
    val editor = _editor.asStateFlow()
    private val _settings = MutableStateFlow(SettingsUiState())
    val settings = _settings.asStateFlow()
    private val _editorDishes = MutableStateFlow<List<RecordSummary>>(emptyList())
    val editorDishes = _editorDishes.asStateFlow()
    private val _relatedDishes = MutableStateFlow(CachedPage())
    val relatedDishes = _relatedDishes.asStateFlow()
    private val _relatedVisits = MutableStateFlow(CachedPage())
    val relatedVisits = _relatedVisits.asStateFlow()
    private var relatedJob: Job? = null
    private val _apply = MutableStateFlow<ApplyUiState?>(null)
    val apply = _apply.asStateFlow()
    private val _operationVisible = MutableStateFlow(false)
    val operationVisible = _operationVisible.asStateFlow()
    private val foreground = MutableStateFlow(false)
    private val eventChannel = Channel<ProductEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var editorDishesJob: Job? = null
    private var refreshListAction: (Boolean) -> Unit = {}
    private var refreshDetailAction: () -> Unit = {}
    private var scopeKey = "RESTAURANT::EXCLUDE"
    private var hasActiveList = false
    val restoredTab: String = if (saved.get<String>("tab") == "settings") "settings" else "restaurants"
    private var actionFromEditor = true

    init {
        job.invokeOnCompletion { _settings.value = SettingsUiState(); _editor.value = EditorUiState(); _apply.value = null }
        scope.launch {
            combine(_search.debounce(300), _query, _filters) { text, query, filters -> query.copy(query = text, filters = filters) }
                .distinctUntilChanged().flatMapLatest { query ->
                    channelFlow {
                        val flags = MutableStateFlow(LoadFlags(refreshing = validFilters(query.effectiveFilters)))
                        fun fetch(more: Boolean) {
                            if (!validFilters(query.effectiveFilters)) { flags.value = LoadFlags(error = "请检查日期与评分区间"); return }
                            launch {
                                flags.value = flags.value.copy(refreshing = !more, more = more, error = null)
                                try {
                                    repository.refreshList(accountId, query, more)
                                    flags.value = LoadFlags()
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { flags.value = LoadFlags(offline = isOffline(e), error = errorText(e)) }
                            }
                        }
                        refreshListAction = { more -> if (!flags.value.refreshing && !flags.value.more) fetch(more) }
                        // The Room subscription is launched before the network request.
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            combine(repository.observeList(accountId, query), flags) { cache, status ->
                                ListUiState(deduplicate(cache.items), status.refreshing, status.more, status.offline, status.error, query,
                                    cache.nextCursor, cache.hasMore, cache.fetchedAt, cache.localScope)
                            }.collect { send(it) }
                        }
                        if (validFilters(query.effectiveFilters)) fetch(false) else flags.value = LoadFlags(error = "请检查日期与评分区间")
                        awaitCancellation()
                    }
                }.collect { _list.value = it }
        }
        scope.launch {
            _detailRef.flatMapLatest { ref -> if (ref == null) flowOf(DetailUiState()) else channelFlow {
                val flags = MutableStateFlow(LoadFlags(refreshing = true))
                fun fetch() { launch {
                    flags.value = flags.value.copy(refreshing = true, error = null)
                    try { repository.refreshDetail(accountId, ref); flags.value = LoadFlags() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { flags.value = LoadFlags(offline = isOffline(e), error = errorText(e)) }
                } }
                refreshDetailAction = { if (!flags.value.refreshing) fetch() }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    combine(repository.observeDetail(accountId, ref), flags) { cached, status -> DetailUiState(cached, status.refreshing, status.offline, status.error) }.collect { send(it) }
                }
                fetch(); awaitCancellation()
            } }.collect { _detail.value = it }
        }
            scope.launch {
            combine(repository.connectivity, foreground) { online, active -> online && active }
                .distinctUntilChanged().filter { it }.collect {
                // Foreground recovery refreshes only page one and the open detail.
                refresh(); refreshDetail()
            }
        }
    }
    private fun filterKey(name: String) = "$scopeKey:$name"
    fun activateList(kind: RecordKind, restaurantId: String? = null, deleted: DeletedFilter = DeletedFilter.EXCLUDE,
        historyEntityKind: RecordKind? = null, historyEntityId: String? = null) {
        val nextKey = "${kind.name}:${restaurantId ?: ""}:${deleted.name}:${historyEntityKind?.name ?: ""}:${historyEntityId ?: ""}"
        if (hasActiveList && scopeKey == nextKey) return
        hasActiveList = true
        scopeKey = nextKey
        _search.value = saved[filterKey("q")] ?: ""
        _filters.value = ListFilters(saved[filterKey("category")], saved[filterKey("cuisine")] ?: "", saved[filterKey("min")], saved[filterKey("max")], saved[filterKey("unrated")] ?: false,
            saved[filterKey("visited")], saved[filterKey("from")] ?: "", saved[filterKey("to")] ?: "", saved[filterKey("sort")] ?: "updated_desc")
        _query.value = ListQuery(kind, restaurantId, deleted = deleted, historyEntityKind = historyEntityKind, historyEntityId = historyEntityId)
    }
    fun setSearch(value: String) { _search.value = value; saved[filterKey("q")] = value }
    fun setFilters(value: ListFilters) {
        _filters.value = value
        saved[filterKey("category")] = value.category; saved[filterKey("cuisine")] = value.cuisine
        saved[filterKey("min")] = value.minRating; saved[filterKey("max")] = value.maxRating
        saved[filterKey("unrated")] = value.unrated; saved[filterKey("visited")] = value.visited
        saved[filterKey("from")] = value.from; saved[filterKey("to")] = value.to; saved[filterKey("sort")] = value.sort
    }
    fun selectTab(tab: String) { saved["tab"] = if (tab == "settings") "settings" else "restaurants" }
    fun setForeground(active: Boolean) { foreground.value = active }
    fun refresh() { refreshListAction(false) }
    fun loadMore() { if (_list.value.hasMore) refreshListAction(true) }
    fun openDetail(ref: RecordRef) {
        if (_detailRef.value == ref) refreshDetail() else _detailRef.value = ref
        relatedJob?.cancel(); _relatedDishes.value = CachedPage(); _relatedVisits.value = CachedPage()
        if (ref.kind == RecordKind.RESTAURANT) relatedJob = scope.launch {
            listOf(RecordKind.DISH, RecordKind.VISIT).forEach { kind -> launch {
                repository.observeList(accountId, ListQuery(kind, ref.id)).collect { if (kind == RecordKind.DISH) _relatedDishes.value = it else _relatedVisits.value = it }
            } }
            refreshRelated()
        }
    }
    private fun refreshRelated() {
        val ref = _detailRef.value ?: return
        if (ref.kind == RecordKind.RESTAURANT) listOf(RecordKind.DISH, RecordKind.VISIT).forEach { kind -> scope.launch {
            try { repository.refreshList(accountId, ListQuery(kind, ref.id)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        } }
    }
    fun refreshDetail() { refreshDetailAction(); refreshRelated() }
    fun openEditor(ref: RecordRef, create: Boolean) {
        val key = ref to create
        if (editorKey == key && (_editor.value.input != null || _editor.value.loading)) return
        editorKey = key
        editorLoadJob?.cancel()
        _editor.value = EditorUiState(loading = true); actionFromEditor = true
        _operationVisible.value = false
        editorLoadJob = scope.launch {
            try {
                val input = if (create) {
                    val fields = editableFields(ref.kind).associateWith { field -> when (field) {
                        "name" -> ""; "category" -> "restaurant"; "dining_date" -> LocalDate.now().toString(); else -> null
                    } }
                    SaveInput(accountId, ref, WriteAction.CREATE, null, fields,
                        firstVisit = if (ref.kind == RecordKind.RESTAURANT) FirstVisitInput(UUID.randomUUID().toString(), LocalDate.now().toString()) else null)
                } else {
                    val base = repository.observeDetail(accountId, ref).first().snapshot ?: repository.refreshDetail(accountId, ref)
                    SaveInput(accountId, ref, if (ref.kind == RecordKind.REVISION) WriteAction.METADATA else WriteAction.UPDATE, base, base.editable)
                }
                _editor.value = EditorUiState(input)
                watchEditorDishes(input)
                if (!create) scope.launch { try { repository.refreshDetail(accountId, ref) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _editor.value = EditorUiState(message = errorText(e)) }
        }
    }
    private fun watchEditorDishes(input: SaveInput) {
        editorDishesJob?.cancel(); _editorDishes.value = emptyList()
        if (input.ref.kind != RecordKind.RESTAURANT || input.baseSnapshot == null) return
        val query = ListQuery(RecordKind.DISH, input.ref.id)
        editorDishesJob = scope.launch {
            launch { try { repository.refreshList(accountId, query) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
            repository.observeList(accountId, query).collect { _editorDishes.value = it.items }
        }
    }
    fun editField(field: String, value: String?) {
        val current = _editor.value.input ?: return
        if (_editor.value.saving || current.action !in setOf(WriteAction.CREATE, WriteAction.UPDATE, WriteAction.METADATA)) return
        _editor.value = _editor.value.copy(input = current.copy(localFields = current.localFields + (field to value)), message = null, errors = emptyMap())
    }
    fun editFirstVisit(value: FirstVisitInput?) {
        val current = _editor.value.input ?: return
        if (_editor.value.saving) return
        _editor.value = _editor.value.copy(input = current.copy(firstVisit = value), message = null)
    }
    fun leaveEditor(onReady: () -> Unit) {
        if (_editor.value.saving) return
        editorKey = null; editorLoadJob?.cancel()
        editorDishesJob?.cancel(); _editorDishes.value = emptyList()
        _editor.value = EditorUiState(); onReady()
    }
    fun saveEditor() {
        if (_editor.value.saving) return
        val input = _editor.value.input ?: return
        val errors = validateInput(input)
        if (errors.isNotEmpty()) { _editor.value = _editor.value.copy(errors = errors, message = "请检查标记的输入"); return }
        // Freeze the visible fields synchronously before any suspension.
        _editor.value = _editor.value.copy(saving = true, message = null)
        scope.launch {
            try { handleOutcome(repository.submit(input)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _editor.value = _editor.value.copy(saving = false, message = "保存失败，请检查网络后重试") }
        }
    }
    private suspend fun handleOutcome(outcome: SaveOutcome) {
        when (outcome) {
            is SaveOutcome.Saved -> {
                editorKey = null
                _editor.value = EditorUiState(message = "已保存到服务器")
                _operationVisible.value = false
                refresh(); refreshDetail(); _apply.value = null
                eventChannel.send(ProductEvent.Saved(outcome.snapshot.ref, actionFromEditor))
            }
            is SaveOutcome.Failed -> _editor.value = EditorUiState(outcome.input, message = outcome.message)
            is SaveOutcome.Conflict -> _editor.value = EditorUiState(outcome.input, conflict = outcome.comparison, message = "服务器已有更新，请比较未提交值")
        }
    }
    fun confirmConflict(choices: Map<String, ConflictChoice>) {
        val comparison = _editor.value.conflict ?: return
        val input = comparison.confirmed(choices)
        if (input.changedFields.isEmpty()) {
            scope.launch {
                editorKey = null
                _editor.value = EditorUiState(message = "已保留服务器值")
                _operationVisible.value = false
                refresh(); refreshDetail(); eventChannel.send(ProductEvent.Saved(comparison.serverCurrent.ref, actionFromEditor))
            }
            return
        }
        _editor.value = EditorUiState(input)
        saveEditor()
    }
    fun dismissComparison() {
        if (_editor.value.saving) return
        _editor.value = _editor.value.copy(conflict = null)
    }
    fun dismissOperation() { if (!_editor.value.saving) _operationVisible.value = false }
    fun mutate(snapshot: RecordSnapshot, action: WriteAction) {
        if (_editor.value.saving || _editor.value.loading) return
        actionFromEditor = false
        _editor.value = EditorUiState(SaveInput(accountId, snapshot.ref, action, snapshot, snapshot.editable))
        _operationVisible.value = true; saveEditor()
    }
    fun previewApply(revision: RecordSnapshot) { scope.launch {
        try {
            val kind = RecordKind.valueOf(requireNotNull(revision.fields["entity_type"]).uppercase())
            val ref = RecordRef(kind, requireNotNull(revision.fields["entity_id"]), if (kind == RecordKind.RESTAURANT) null else revision.ref.restaurantId)
            _apply.value = ApplyUiState(revision, repository.refreshDetail(accountId, ref))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _editor.value = EditorUiState(message = errorText(e)); _operationVisible.value = true }
    } }
    fun applySide(side: String) { _apply.value = _apply.value?.copy(side = side, selected = emptySet()) }
    fun selectApply(field: String, selected: Boolean) { _apply.value = _apply.value?.let { it.copy(selected = if (selected) it.selected + field else it.selected - field) } }
    fun dismissApply() { _apply.value = null }
    fun submitApply() {
        val preview = _apply.value ?: return
        if (_editor.value.saving || preview.selected.isEmpty() || preview.current.deletedAt != null) return
        val values = preview.current.editable + preview.eligible.filter { it.field in preview.selected }.associate { it.field to preview.proposed(it) }
        val input = SaveInput(accountId, preview.current.ref, WriteAction.APPLY, preview.current, values,
            sourceRevisionId = preview.source.ref.id, applySide = preview.side, applyFields = preview.selected.toList())
        _apply.value = null; actionFromEditor = false; _editor.value = EditorUiState(input); _operationVisible.value = true; saveEditor()
    }
    fun loadSettings() { scope.launch {
        _settings.value = _settings.value.copy(loading = true, message = null)
        try { coroutineScope {
            val profile = async { security.profile() }; val sessions = async { security.sessions() }
            _settings.value = _settings.value.copy(profile = profile.await(), sessions = sessions.await(), loading = false)
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _settings.value = _settings.value.copy(loading = false, message = errorText(e)) }
    } }
    fun revokeSession(id: String) { scope.launch {
        _settings.value = _settings.value.copy(busy = true)
        try { security.revokeSession(id); _settings.value = _settings.value.copy(busy = false, message = "会话已撤销"); loadSettings() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _settings.value = _settings.value.copy(busy = false, message = errorText(e)) }
    } }
    fun editPassword(field: String, value: String) {
        val state = _settings.value
        if (state.busy || state.passwordUnknown) return
        val inputs = state.passwordInputs
        _settings.value = state.copy(passwordInputs = when (field) {
            "current-password" -> inputs.copy(current = value)
            "new-password" -> inputs.copy(replacement = value)
            else -> inputs.copy(repeated = value)
        })
    }
    fun changePassword(current: String, replacement: String) {
        if (_settings.value.busy) return
        _settings.value = _settings.value.copy(busy = true, message = null)
        scope.launch {
        try { security.changePassword(current, replacement)
            _settings.value = _settings.value.copy(busy = false, passwordUnknown = false, passwordInputs = PasswordInputs(), passwordRevision = _settings.value.passwordRevision + 1, message = "密码已修改，其他会话已退出")
            loadSettings()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _settings.value = _settings.value.copy(busy = false, passwordUnknown = e is ProductFailure && e.code == "AUTH_RESULT_UNKNOWN", message = errorText(e)) }
    } }
    override fun onCleared() { job.cancel(); _settings.value = SettingsUiState() }
}
fun validFilters(filters: ListFilters): Boolean =
    (filters.from.isBlank() || validDate(filters.from)) && (filters.to.isBlank() || validDate(filters.to)) &&
        (filters.from.isBlank() || filters.to.isBlank() || filters.from <= filters.to) &&
        (filters.minRating == null || filters.maxRating == null || requireNotNull(filters.minRating) <= requireNotNull(filters.maxRating))
fun validateInput(input: SaveInput): Map<String, String> = buildMap {
    if (input.action in setOf(WriteAction.DELETE, WriteAction.RESTORE)) return@buildMap
    val kind = input.ref.kind
    val required = when (kind) { RecordKind.RESTAURANT -> setOf("name", "category"); RecordKind.DISH -> setOf("name"); RecordKind.VISIT -> setOf("dining_date"); else -> emptySet() }
    required.forEach { if (input.localFields[it].isNullOrBlank()) put(it, "请填写${fieldLabel(it)}") }
    input.localFields.forEach { (field, value) ->
        val limit = when (field) { "name" -> 120; "cuisine" -> 60; "address" -> 500; "review", "note" -> if (kind == RecordKind.RESTAURANT) 4000 else if (kind == RecordKind.REVISION) 500 else 2000; else -> Int.MAX_VALUE }
        if (value != null && (value.codePointCount(0, value.length) > limit || '\u0000' in value)) put(field, "最多 $limit 个字符，不能含空字符")
    }
    input.localFields["rating_tenths"]?.let { if (it.toIntOrNull() !in 10..50) put("rating_tenths", "评分需 ★ 1.0 至 ★ 5.0") }
    if (kind == RecordKind.VISIT && !validDate(input.localFields["dining_date"] ?: "")) put("dining_date", "请输入有效日期 YYYY-MM-DD（1900–2100）")
    input.firstVisit?.let { if (!validDate(it.date)) put("first_visit", "请输入有效首访日期 YYYY-MM-DD（1900–2100）") }
    input.firstVisit?.note?.let { if (it.codePointCount(0, it.length) > 2000 || '\u0000' in it) put("first_visit_note", "首访备注最多 2000 个字符，不能含空字符") }
}
fun isOffline(error: Exception) = error is ProductFailure && error.offline || error is AuthFailure && error.code == "NETWORK_ERROR"
fun errorText(error: Exception): String = when (error) {
    is AuthFailure -> top.alanvampuke.dininglog.feature.auth.authErrorText(error)
    is ProductFailure -> when (error.code) {
        "NETWORK_ERROR" -> "连接失败，保留离线缓存；可手动重试"
        "NOT_FOUND", "PARENT_UNAVAILABLE" -> "记录或父餐厅不可用，请刷新或先恢复餐厅"
        "RATE_LIMITED" -> "请求过于频繁，请在 ${error.retryAfterSeconds ?: 60} 秒后重试"
        "AUTH_RESULT_UNKNOWN" -> "修改密码结果未知；保留原输入，重试确认结果"
        "AUTH_RETRY_EXPIRED" -> "确认窗口已过，请退出后用新密码或旧密码重新登录确认"
        else -> "操作失败（${error.code}），可重试"
    }
    else -> "操作失败，请重试"
}
