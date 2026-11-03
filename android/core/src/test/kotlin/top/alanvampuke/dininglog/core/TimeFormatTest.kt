package top.alanvampuke.dininglog.core

import kotlin.test.*
import java.time.ZoneId
import java.util.TimeZone

/**
 * M1/M2 regression cover. The list card and the detail screen must agree on which
 * local day a timestamp falls in, and diff values must not leak raw ISO strings.
 * Time zone and locale are pinned so the assertions do not depend on the CI host.
 */
class TimeFormatTest {
    private val originalZone: TimeZone = TimeZone.getDefault()

    @AfterTest
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun absoluteTimeRendersInTheDeviceTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("America/Los_Angeles")))
        // 00:12 UTC is still the previous day in Los Angeles.
        assertEquals("2026-11-02 16:12", formatAbsoluteTime("2026-11-03T00:12:12.491Z"))
    }

    @Test
    fun listCardDateAndDetailMinuteAgreeAcrossMidnight() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("America/Los_Angeles")))
        val iso = "2026-11-03T00:12:12.491Z"
        // The card keeps date precision; the detail keeps minute precision.
        assertEquals("2026-11-02", formatAbsoluteTime(iso).take(10))
        assertEquals("2026-11-02 16:12", formatAbsoluteTime(iso))
        // The raw UTC prefix would have said 2026-11-03, which is the bug M1 fixed.
        assertNotEquals(iso.take(10), formatAbsoluteTime(iso).take(10))
    }

    @Test
    fun millisAndIsoHelpersProduceTheSameText() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")))
        val iso = "2026-11-03T00:12:12.491Z"
        val millis = java.time.Instant.parse(iso).toEpochMilli()
        assertEquals(formatAbsoluteTime(iso), formatAbsoluteTimeMillis(millis))
        assertEquals("2026-11-03 08:12", formatAbsoluteTime(iso))
    }

    @Test
    fun unparseableInputFallsBackInsteadOfCrashing() {
        assertEquals("not-a-date", formatAbsoluteTime("not-a-date"))
        // An empty value must stay empty rather than inventing a timestamp.
        assertEquals("", formatAbsoluteTime(""))
    }

    @Test
    fun deletedAtDiffValueIsFormattedNotRawIso() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")))
        val iso = "2026-11-03T00:12:12.491Z"
        assertEquals("2026-11-03 08:12", displayValue("deleted_at", iso))
        assertNotEquals(iso, displayValue("deleted_at", iso))
    }

    @Test
    fun deletedAtDiffStillShowsTheNotFilledPlaceholderWhenNull() {
        assertEquals("未填写", displayValue("deleted_at", null))
        assertEquals("未填写", displayValue("deleted_at", ""))
    }

    @Test
    fun businessDatesAreNotPassedThroughTheTimeFormatter() {
        // dining_date / last_visit_date are calendar days, not instants; they must
        // render verbatim so a time zone can never shift them by a day.
        assertEquals("2026-11-03", displayValue("dining_date", "2026-11-03"))
        assertEquals("2026-11-03", displayValue("last_visit_date", "2026-11-03"))
    }
}
