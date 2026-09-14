package com.guanjian.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MonthlyActivityTest {
    @Test fun aggregatesFocusReadingBooksAndCompletedPlansByDay() {
        val events = listOf(
            StatEvent("2026-09-03", "FOCUS_COMPLETED", focusMillis = 45 * 60_000L),
            StatEvent("2026-09-03", "PROGRESS", pages = 20, sourceId = "book-a"),
            StatEvent("2026-09-03", "PROGRESS", pages = 5, sourceId = "book-a"),
            StatEvent("2026-09-03", "PROGRESS", pages = 8, sourceId = "book-b"),
            StatEvent("2026-09-03", "PLAN_COMPLETED"),
            StatEvent("2026-08-31", "FOCUS_COMPLETED", focusMillis = 90 * 60_000L)
        )

        val day = monthlyActivity(YearMonth.of(2026, 9), events).single { it.date == LocalDate.of(2026, 9, 3) }

        assertEquals(45, day.focusMinutes)
        assertEquals(2, day.readingBooks)
        assertEquals(33, day.readingPages)
        assertEquals(1, day.completedPlans)
        assertEquals(3, day.level)
    }

    @Test fun monthGridStartsOnMondayAndEndsOnSunday() {
        val grid = monthlyActivityGrid(YearMonth.of(2026, 9), emptyList())

        assertEquals(35, grid.size)
        assertNull(grid[0])
        assertEquals(LocalDate.of(2026, 9, 1), grid[1]?.date)
        assertEquals(LocalDate.of(2026, 9, 30), grid[30]?.date)
        assertNull(grid.last())
    }

    @Test fun intensityIsZeroWithoutActivityAndCappedAtFour() {
        val events = listOf(
            StatEvent("2026-09-10", "FOCUS_COMPLETED", focusMillis = 120 * 60_000L),
            StatEvent("2026-09-10", "PROGRESS", pages = 30, sourceId = "book-a"),
            StatEvent("2026-09-10", "PROGRESS", pages = 10, sourceId = "book-b"),
            StatEvent("2026-09-10", "PLAN_COMPLETED"),
            StatEvent("2026-09-10", "PLAN_COMPLETED")
        )
        val days = monthlyActivity(YearMonth.of(2026, 9), events)

        assertEquals(0, days.first { it.date.dayOfMonth == 1 }.level)
        assertEquals(4, days.first { it.date.dayOfMonth == 10 }.level)
    }
}
