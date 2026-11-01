package top.alanvampuke.dininglog.feature

import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.core.auth.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProductViewModelTest {
    private val ref = RecordRef(RecordKind.RESTAURANT, "restaurant")
    private val snapshot = RecordSnapshot(ref, 1, mapOf("name" to "兰记", "category" to "restaurant",
        "address" to "愚园路", "rating_tenths" to "40", "note" to "开始编辑时", "review" to null), "2026-11-01T00:00:00Z")
    private val row = RecordSummary(ref, "兰记", 1, snapshot.updatedAt)

    private inner class Repository : ProductRepository {
        override val connectivity = MutableStateFlow(true)
        val page = MutableStateFlow(CachedPage(listOf(row), "cursor", true, 1, false))
        val detail = MutableStateFlow(CachedDetail(snapshot, 1))
        val drafts = MutableStateFlow<List<SavedDraft>>(emptyList())
        val refreshes = mutableListOf<Pair<ListQuery, Boolean>>()
        val stored = mutableListOf<SavedDraft>()
        val submitted = mutableListOf<SavedDraft>()
        var refreshBody: suspend (ListQuery, Boolean) -> Unit = { _, _ -> }
        var storeGate: CompletableDeferred<Unit>? = null
        var outcome: (SavedDraft) -> SaveOutcome = { SaveOutcome.Saved(snapshot.copy(version = 2, fields = it.localFields)) }
        override fun observeList(accountId: String, query: ListQuery) = page
        override suspend fun refreshList(accountId: String, query: ListQuery, loadMore: Boolean) {
            refreshes += query to loadMore; refreshBody(query, loadMore)
        }
        override fun observeDetail(accountId: String, ref: RecordRef) = detail
        override suspend fun refreshDetail(accountId: String, ref: RecordRef) = snapshot
        override fun observeDrafts(accountId: String) = drafts
        override suspend fun findDraft(accountId: String, draftId: String) = drafts.value.firstOrNull { it.id == draftId }
        override suspend fun storeDraft(draft: SavedDraft) {
            storeGate?.await(); stored += draft; drafts.value = listOf(draft)
        }
        override suspend fun discardDraft(accountId: String, draftId: String) { drafts.value = emptyList() }
        override suspend fun submit(draft: SavedDraft): SaveOutcome { submitted += draft; return outcome(draft) }
        override suspend fun confirmExpired(draft: SavedDraft, current: RecordSnapshot?) = error("unexpected")
    }
    private class Security : AccountSecurity {
        var unknown = false
        override suspend fun profile() = AccountInfo("A", "alice", "Alice", "2026-11-01")
        override suspend fun sessions() = emptyList<DeviceSession>()
        override suspend fun revokeSession(id: String) { }
        override suspend fun changePassword(current: String, replacement: String) {
            if (unknown) throw ProductFailure("AUTH_RESULT_UNKNOWN")
        }
    }
    private suspend fun TestScope.fixture(block: suspend (ProductViewModel, Repository, AccountSession, Security) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val file = object : AtomicTokenFile {
            var bytes: ByteArray? = null
            override fun read() = bytes
            override fun replace(bytes: ByteArray) { this.bytes = bytes }
            override fun delete() { bytes = null }
        }
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val keys = object : KeyManager { override fun existingKey() = key; override fun encryptionKey() = key }
        val account = AccountSession(SecureTokenStore(file, AesGcmTokenCipher(keys, "test")), AccountMemory())
        account.install(AuthSession("A", "alice", "session", "access", "refresh"))
        val repository = Repository(); val security = Security()
        val vm = ProductViewModel("A", repository, security, account)
        try { block(vm, repository, account, security) }
        finally { account.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun TestScope.start() { runCurrent(); advanceTimeBy(300); runCurrent() }

    @Test fun refreshFailureAndPaginationKeepVisibleRowsAndDeduplicateIds() = runTest { fixture { vm, repo, _, _ ->
        val first = CompletableDeferred<Unit>(); val more = CompletableDeferred<Unit>()
        repo.refreshBody = { _, loadMore -> if (loadMore) more.await() else first.await() }
        start()
        assertTrue(vm.list.value.isRefreshing); assertEquals(listOf(row), vm.list.value.items)
        first.complete(Unit); runCurrent()
        vm.loadMore(); runCurrent()
        assertTrue(vm.list.value.isLoadingMore); assertEquals(listOf(row), vm.list.value.items)
        repo.page.value = repo.page.value.copy(items = listOf(row, row.copy(title = "兰记新名"), row.copy(ref = ref.copy(id = "second"))))
        more.complete(Unit); runCurrent()
        assertEquals(listOf("restaurant", "second"), vm.list.value.items.map { it.ref.id })
        assertEquals("兰记新名", vm.list.value.items.first().title)
        repo.refreshBody = { _, _ -> throw ProductFailure("NETWORK_ERROR") }
        vm.refresh(); runCurrent()
        assertTrue(vm.list.value.isOffline); assertNotNull(vm.list.value.error)
        assertEquals(2, vm.list.value.items.size); assertFalse(vm.list.value.isRefreshing)
    } }

    @Test fun searchDebouncesAndFilterChangeCancelsThePreviousNetworkQuery() = runTest { fixture { vm, repo, _, _ ->
        val cancelled = mutableListOf<ListQuery>()
        repo.refreshBody = { query, _ -> try { awaitCancellation() } finally { cancelled += query } }
        start(); assertEquals(1, repo.refreshes.size)
        vm.setSearch("川菜"); runCurrent(); advanceTimeBy(299); runCurrent()
        assertEquals(1, repo.refreshes.size)
        advanceTimeBy(1); runCurrent()
        assertEquals("川菜", repo.refreshes.last().first.query)
        vm.setFilters(ListFilters(category = "cafe")); runCurrent()
        assertEquals("cafe", repo.refreshes.last().first.filters.category)
        assertTrue(cancelled.any { it.query == "川菜" && it.filters.category == null })
        assertEquals("cafe", vm.list.value.query.filters.category)
    } }

    @Test fun saveLocksInputBeforeSuspendingRoomFlushAndSubmitsOnce() = runTest { fixture { vm, repo, _, _ ->
        start(); vm.openEditor(ref, false); runCurrent()
        vm.editField("note", "点击保存时的内容")
        val gate = CompletableDeferred<Unit>(); repo.storeGate = gate
        vm.saveEditor()
        assertTrue(vm.editor.value.saving)
        vm.editField("note", "不能替换在途请求"); vm.saveEditor(); runCurrent()
        assertEquals("点击保存时的内容", vm.editor.value.draft!!.localFields["note"])
        assertTrue(repo.submitted.isEmpty())
        gate.complete(Unit); runCurrent()
        assertEquals(1, repo.submitted.size)
        assertEquals("点击保存时的内容", repo.submitted.single().localFields["note"])
    } }

    @Test fun dismissingConflictResumes500msAutosaveWithoutAnAutomaticSubmit() = runTest { fixture { vm, repo, _, _ ->
        start(); vm.openEditor(ref, false); runCurrent(); vm.editField("note", "本机草稿")
        repo.outcome = { draft -> SaveOutcome.Conflict(draft, compareConflict(draft,
            snapshot.copy(version = 2, fields = snapshot.fields + ("note" to "服务器备注")))) }
        vm.saveEditor(); runCurrent(); assertNotNull(vm.editor.value.conflict)
        vm.dismissComparison(); vm.editField("note", "关闭面板后继续输入"); runCurrent()
        val before = repo.stored.size
        advanceTimeBy(499); runCurrent(); assertEquals(before, repo.stored.size)
        advanceTimeBy(1); runCurrent()
        assertEquals("关闭面板后继续输入", repo.stored.last().localFields["note"])
        assertEquals(1, repo.submitted.size); assertEquals(1, vm.editor.value.draft!!.baseVersion)
    } }

    @Test fun connectivityRecoveryRefreshesInForegroundAndNeverUploadsDrafts() = runTest { fixture { vm, repo, _, _ ->
        start(); vm.openEditor(ref, false); runCurrent(); vm.editField("note", "未提交内容")
        val count = repo.refreshes.size
        repo.connectivity.value = false; runCurrent(); repo.connectivity.value = true; runCurrent()
        assertEquals(count, repo.refreshes.size)
        vm.setForeground(true); runCurrent()
        assertEquals(count + 1, repo.refreshes.size); assertTrue(repo.submitted.isEmpty())
        vm.setForeground(false); runCurrent(); repo.connectivity.value = false; runCurrent(); repo.connectivity.value = true; runCurrent()
        assertEquals(count + 1, repo.refreshes.size); assertTrue(repo.submitted.isEmpty())
    } }

    @Test fun unknownPasswordKeepsOriginalInputsInMemoryAndAccountExitClearsThem() = runTest { fixture { vm, _, account, security ->
        start(); vm.editPassword("current-password", "current-password-fixture")
        vm.editPassword("new-password", "replacement-password-fixture"); vm.editPassword("repeat-password", "replacement-password-fixture")
        security.unknown = true
        vm.changePassword(vm.settings.value.passwordInputs.current, vm.settings.value.passwordInputs.replacement); runCurrent()
        assertTrue(vm.settings.value.passwordUnknown)
        vm.editPassword("new-password", "different-request")
        assertEquals("replacement-password-fixture", vm.settings.value.passwordInputs.replacement)
        account.clear(); runCurrent()
        assertEquals(PasswordInputs(), vm.settings.value.passwordInputs)
    } }

    @Test fun reopeningAnUnknownDeleteKeepsStoredPayloadAndKeyUntilManualRetry() = runTest { fixture { vm, repo, _, _ ->
        start()
        val original = OriginalRequest("DELETE", "restaurants/${ref.id}", "{\"expected_version\":1}", "original-delete-key", 1000)
        val pending = SavedDraft("A", "DELETE:RESTAURANT:${ref.id}", ref, WriteAction.DELETE,
            snapshot, snapshot.editable, status = DraftStatus.RESULT_UNKNOWN, original = original)
        repo.drafts.value = listOf(pending)
        vm.mutate(snapshot.copy(version = 2), WriteAction.DELETE); runCurrent()
        assertTrue(vm.operationVisible.value)
        assertEquals(pending, vm.editor.value.draft)
        assertTrue(repo.submitted.isEmpty(), "Reopening a pending operation must ask for manual retry")
        vm.dismissOperation(); vm.mutate(snapshot.copy(version = 3), WriteAction.DELETE); runCurrent()
        assertEquals(original, vm.editor.value.draft!!.original)
        vm.saveEditor(); runCurrent()
        assertEquals(listOf(pending), repo.submitted)
    } }

    @Test fun reopeningUnknownHistoryApplyCannotReplaceItsSelectedFieldsOrRequest() = runTest { fixture { vm, repo, _, _ ->
        start()
        val revision = RecordSnapshot(RecordRef(RecordKind.REVISION, "revision", ref.id), 1,
            mapOf("entity_type" to "restaurant", "entity_id" to ref.id), snapshot.updatedAt,
            diff = listOf(Change("rating_tenths", "40", "43")))
        val original = OriginalRequest("POST", "revisions", "original-apply-payload", "original-apply-key", 1000)
        val pending = SavedDraft("A", "apply:revision", ref, WriteAction.APPLY, snapshot,
            snapshot.editable + ("rating_tenths" to "43"), sourceRevisionId = "revision",
            applySide = "after", applyFields = listOf("rating_tenths"), status = DraftStatus.RESULT_UNKNOWN, original = original)
        repo.drafts.value = listOf(pending)
        vm.previewApply(revision); runCurrent()
        assertNull(vm.apply.value)
        assertEquals(pending, vm.editor.value.draft)
        assertTrue(vm.operationVisible.value); assertTrue(repo.submitted.isEmpty())
        vm.saveEditor(); runCurrent()
        assertEquals(original, repo.submitted.single().original)
        assertEquals(listOf("rating_tenths"), repo.submitted.single().applyFields)
    } }
}
