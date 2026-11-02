package top.alanvampuke.dininglog.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*
import top.alanvampuke.dininglog.data.local.*

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class RoomCacheTest {
    private lateinit var db: ProductDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ProductDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }
    @Test fun accountsHaveIsolatedCompositeKeysAndLogoutClearsEveryTable() = runBlocking {
        val dao = db.products()
        dao.cacheSummary(SummaryRow("A", "RESTAURANT", "same-id", 1, "A private", 1))
        dao.cacheSummary(SummaryRow("B", "RESTAURANT", "same-id", 1, "B private", 1))
        assertEquals("A private", dao.observeSummaries("A").first().single().payload)
        assertEquals("B private", dao.observeSummaries("B").first().single().payload)
        dao.cacheDetail(DetailRow("A", "RESTAURANT", "same-id", 1, "A complete", 1))
        dao.putQuery(QueryRow("A", "query", "cursor", true, 1))
        dao.putMembers(listOf(PageMember("A", "query", "RESTAURANT", "same-id", 0)))
        assertTrue(dao.observeDetails("B").first().isEmpty())
        OwnerCache(db).clear()
        assertTrue(dao.observeSummaries("A").first().isEmpty()); assertTrue(dao.observeSummaries("B").first().isEmpty())
        assertTrue(dao.observeDetails("B").first().isEmpty())
        assertTrue(dao.observeDetails("A").first().isEmpty())
        assertNull(dao.query("A", "query")); assertTrue(dao.members("A", "query").isEmpty())
    }
    @Test fun summariesNeverTruncateDetailAndHigherVersionsMarkItStale() = runBlocking {
        val dao = db.products()
        val complete = "长评价".repeat(1200)
        dao.cacheDetail(DetailRow("A", "RESTAURANT", "id", 1, complete, 1))
        dao.cacheSummary(SummaryRow("A", "RESTAURANT", "id", 2, "120-char-excerpt", 2))
        val older = dao.observeDetail("A", "RESTAURANT", "id").first()!!
        assertEquals(complete, older.payload); assertTrue(older.stale)
        dao.cacheDetail(DetailRow("A", "RESTAURANT", "id", 2, "new complete", 3))
        assertFalse(dao.observeDetail("A", "RESTAURANT", "id").first()!!.stale)
        dao.cacheDetail(DetailRow("A", "RESTAURANT", "id", 1, "late response", 4))
        assertEquals("new complete", dao.observeDetail("A", "RESTAURANT", "id").first()!!.payload)
        assertEquals("120-char-excerpt", dao.observeSummaries("A").first().single().payload)
    }
    @Test fun versionOneUpgradeDropsOnlyObsoleteTableAndPreservesAllCacheRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "p8-migration-${java.util.UUID.randomUUID()}.db"
        var disk = Room.databaseBuilder(context, ProductDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val dao = disk.products()
            dao.putSummary(SummaryRow("A", "RESTAURANT", "id", 2, "summary", 10))
            dao.putDetail(DetailRow("A", "RESTAURANT", "id", 2, "detail", 10))
            dao.putQuery(QueryRow("A", "query", "cursor", true, 10))
            dao.putMembers(listOf(PageMember("A", "query", "RESTAURANT", "id", 0)))
            // The four retained tables are unchanged from v1. Recreate its removed table and user_version.
            disk.openHelper.writableDatabase.execSQL("CREATE TABLE saved_drafts (account_id TEXT NOT NULL, draft_id TEXT NOT NULL, PRIMARY KEY(account_id, draft_id))")
            disk.openHelper.writableDatabase.execSQL("INSERT INTO saved_drafts VALUES ('A','old')")
            disk.openHelper.writableDatabase.version = 1
            disk.close()
            disk = Room.databaseBuilder(context, ProductDatabase::class.java, name)
                .addMigrations(ProductDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
            assertEquals("summary", disk.products().observeSummaries("A").first().single().payload)
            assertEquals("detail", disk.products().detail("A", "RESTAURANT", "id")!!.payload)
            assertEquals("cursor", disk.products().query("A", "query")!!.cursor)
            assertEquals("id", disk.products().members("A", "query").single().entityId)
            disk.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name='saved_drafts'").use {
                assertFalse(it.moveToFirst())
            }
            assertEquals(2, disk.openHelper.readableDatabase.version)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

}
