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
        val refreshes = mutableListOf<Pair<ListQuery, Boolean>>()
        val submitted = mutableListOf<SaveInput>()
        var refreshBody: suspend (ListQuery, Boolean) -> Unit = { _, _ -> }
        var submitGate: CompletableDeferred<Unit>? = null
        var outcome: (SaveInput) -> SaveOutcome = { SaveOutcome.Saved(snapshot.copy(version = 2, fields = it.localFields)) }
        override fun observeList(accountId: String, query: ListQuery) = page
        override suspend fun refreshList(accountId: String, query: ListQuery, loadMore: Boolean) {
            refreshes += query to loadMore; refreshBody(query, loadMore)
        }
        override fun observeDetail(accountId: String, ref: RecordRef) = detail
        override suspend fun refreshDetail(accountId: String, ref: RecordRef) = snapshot
        override suspend fun submit(input: SaveInput): SaveOutcome { submitGate?.await(); submitted += input; return outcome(input) }

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

    @Test fun restaurantWithOnlyNameAndCategoryCanBeSavedAndOptionalTextStillHasLimits() {
        val minimal = SaveInput("A", ref, WriteAction.CREATE, null,
            mapOf("name" to "只填名称", "category" to "restaurant"))
        assertTrue(validateInput(minimal).isEmpty())
        for (address in listOf(null, "", "   ")) {
            assertTrue(validateInput(minimal.copy(localFields = minimal.localFields + ("address" to address))).isEmpty())
        }
        assertEquals(setOf("name"), validateInput(minimal.copy(localFields = minimal.localFields + ("name" to "   "))).keys)
        assertEquals(setOf("category"), validateInput(minimal.copy(localFields = minimal.localFields - "category")).keys)
        assertEquals(setOf("address"), validateInput(minimal.copy(localFields = minimal.localFields + ("address" to "x".repeat(501)))).keys)
    }

    @Test fun newRestaurantEditorSubmitsWithOnlyANameAndTheDefaultCategory() = runTest { fixture { vm, repo, _, _ ->
        start(); vm.openEditor(ref, true); runCurrent()
        assertEquals("restaurant", vm.editor.value.input!!.localFields["category"])
        assertNull(vm.editor.value.input!!.localFields["address"])
        vm.editField("name", "只填名称的小馆")
        vm.saveEditor(); runCurrent()
        assertTrue(vm.editor.value.errors.isEmpty())
        assertEquals(1, repo.submitted.size)
        val saved = repo.submitted.single()
        assertEquals(WriteAction.CREATE, saved.action)
        assertEquals("只填名称的小馆", saved.localFields["name"])
        assertEquals("restaurant", saved.localFields["category"])
        for (field in listOf("address", "cuisine", "review", "note")) assertNull(saved.localFields[field])
        assertEquals("已保存到服务器", vm.editor.value.message)
        assertFalse(vm.editor.value.saving)
    } }

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

    @Test fun saveLocksInputBeforeSuspendingSubmissionAndSubmitsOnce() = runTest { fixture { vm, repo, _, _ ->
        start(); vm.openEditor(ref, false); runCurrent()
        vm.editField("note", "点击保存时的内容")
        val gate = CompletableDeferred<Unit>(); repo.submitGate = gate
        vm.saveEditor()
        assertTrue(vm.editor.value.saving)
        vm.editField("note", "不能替换在途请求"); vm.saveEditor(); runCurrent()
        assertEquals("点击保存时的内容", vm.editor.value.input!!.localFields["note"])
        assertTrue(repo.submitted.isEmpty())
        gate.complete(Unit); runCurrent()
        assertEquals(1, repo.submitted.size)
        assertEquals("点击保存时的内容", repo.submitted.single().localFields["note"])
    } }

    @Test fun dismissingConflictKeepsMemoryInputWithoutAnAutomaticSubmit() = runTest { fixture { vm, repo, _, _ ->
        start(); vm.openEditor(ref, false); runCurrent(); vm.editField("note", "未提交值")
        repo.outcome = { input -> SaveOutcome.Conflict(input, compareConflict(input,
            snapshot.copy(version = 2, fields = snapshot.fields + ("note" to "服务器备注")))) }
        vm.saveEditor(); runCurrent(); assertNotNull(vm.editor.value.conflict)
        vm.dismissComparison(); vm.editField("note", "关闭面板后继续输入"); runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals("关闭面板后继续输入", vm.editor.value.input!!.localFields["note"])
        assertEquals(1, repo.submitted.size); assertEquals(1, vm.editor.value.input!!.baseVersion)
    } }

    @Test fun connectivityRecoveryRefreshesInForegroundAndNeverSubmitsUnsavedInput() = runTest { fixture { vm, repo, _, _ ->
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

    @Test fun failedSaveKeepsEditorInputUntilRetryAndLeavingClearsIt() = runTest { fixture { vm, repo, _, _ ->
        start(); vm.openEditor(ref, false); runCurrent(); vm.editField("note", "未提交值")
        repo.outcome = { throw ProductFailure("NETWORK_ERROR") }
        vm.saveEditor(); runCurrent()
        assertFalse(vm.editor.value.saving)
        assertEquals("保存失败，请检查网络后重试", vm.editor.value.message)
        assertEquals("未提交值", vm.editor.value.input!!.localFields["note"])
        vm.editField("note", "重试前修改")
        repo.outcome = { SaveOutcome.Saved(snapshot.copy(version = 2, fields = it.localFields)) }
        vm.saveEditor(); runCurrent()
        assertEquals(2, repo.submitted.size)
        assertEquals("重试前修改", repo.submitted.last().localFields["note"])
        vm.openEditor(ref, false); runCurrent(); vm.editField("note", "离开前输入")
        var left = false; vm.leaveEditor { left = true }
        assertTrue(left); assertNull(vm.editor.value.input)
        vm.openEditor(ref, false); runCurrent()
        assertEquals("开始编辑时", vm.editor.value.input!!.localFields["note"])
    } }

    @Test fun deleteSubmitsCurrentSnapshotAndHistoryApplyKeepsSelectedFields() = runTest { fixture { vm, repo, _, _ ->
        start(); vm.mutate(snapshot.copy(version = 3), WriteAction.DELETE); runCurrent()
        assertEquals(WriteAction.DELETE, repo.submitted.single().action)
        assertEquals(3, repo.submitted.single().baseVersion)
        val revision = RecordSnapshot(RecordRef(RecordKind.REVISION, "revision", ref.id), 1,
            mapOf("entity_type" to "restaurant", "entity_id" to ref.id), snapshot.updatedAt,
            diff = listOf(Change("rating_tenths", "40", "43")))
        vm.previewApply(revision); runCurrent(); assertNotNull(vm.apply.value)
        vm.applySide("after"); vm.selectApply("rating_tenths", true); vm.submitApply(); runCurrent()
        assertEquals(WriteAction.APPLY, repo.submitted.last().action)
        assertEquals(listOf("rating_tenths"), repo.submitted.last().applyFields)
        assertEquals("43", repo.submitted.last().localFields["rating_tenths"])
    } }
    @Test fun legacyVisitTabFallsBackToRestaurantsAndSelectionStoresOnlySupportedTabs() = runTest { fixture { _, repo, account, security ->
        val saved = androidx.lifecycle.SavedStateHandle(mapOf("tab" to "visits"))
        val restored = ProductViewModel("A", repo, security, account, saved)
        assertEquals("restaurants", restored.restoredTab)
        restored.selectTab("visits"); assertEquals("restaurants", saved.get<String>("tab"))
        restored.selectTab("settings"); assertEquals("settings", saved.get<String>("tab"))
        assertNull(restored.editor.value.input)
    } }

}
