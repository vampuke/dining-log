package top.alanvampuke.dininglog.core

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainTest {
    @Test
    fun ratingUsesExactTenthsAndDisplaysOneDecimal() {
        assertEquals("4.3", Rating.fromTenths(43).display)
        assertEquals("1.0", Rating.fromTenths(10).toString())
        assertEquals("5.0", Rating.fromTenths(50).toString())
    }

    @Test
    fun zeroAndOutOfRangeRatingsAreRejected() {
        for (tenths in listOf(0, 9, 51)) {
            assertFailsWith<IllegalArgumentException> { Rating.fromTenths(tenths) }
        }
    }

    @Test
    fun visitKeepsTheEnteredLocalDateAndAcceptsLeapDay() {
        val date = LocalDate.parse("2024-02-29")
        assertEquals(date, visit(date).diningDate)
        assertFailsWith<DateTimeParseException> { LocalDate.parse("2023-02-29") }
    }

    @Test
    fun datesOutsideTheDatabaseRangeAreRejected() {
        assertFailsWith<IllegalArgumentException> { visit(LocalDate.of(1899, 12, 31)) }
        assertFailsWith<IllegalArgumentException> { visit(LocalDate.of(2101, 1, 1)) }
    }

    private fun visit(date: LocalDate) = Visit(
        id = "30000000-0000-4000-8000-000000000001",
        restaurantId = "10000000-0000-4000-8000-000000000001",
        diningDate = date,
        meal = null,
        note = null,
        version = 1,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        deletedAt = null,
    )
}
