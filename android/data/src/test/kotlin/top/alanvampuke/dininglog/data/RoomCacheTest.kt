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
        dao.putDraft(DraftRow("A", "draft", "RESTAURANT", "same-id", 1, "base", "draft-content", "fixed-key", "result_unknown", 1, "original-request"))
        dao.cacheDetail(DetailRow("A", "RESTAURANT", "same-id", 1, "A complete", 1))
        dao.putQuery(QueryRow("A", "query", "cursor", true, 1))
        dao.putMembers(listOf(PageMember("A", "query", "RESTAURANT", "same-id", 0)))
        assertTrue(dao.observeDrafts("B").first().isEmpty())
        OwnerCache(db).clear()
        assertTrue(dao.observeSummaries("A").first().isEmpty()); assertTrue(dao.observeSummaries("B").first().isEmpty())
        assertTrue(dao.observeDrafts("A").first().isEmpty())
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
}
