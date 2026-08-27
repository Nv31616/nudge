package com.astraedus.nudge.ui.screens.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The regression suite for the reported bug: "I click a different day, it shows as selected,
 * but the numbers don't swap to that day."
 *
 * The numbers now come from [StatsDaySelection.selected], and the highlighted bar from
 * [StatsDaySelection.selectedIndex]. These tests pin that the two are always the same day —
 * which is the whole reason the type exists.
 */
class StatsDaySelectionTest {

    private val today = LocalDate.of(2026, 8, 27) // a Thursday

    // --- Initial state ---

    @Test
    fun `starts on today with today as the last bar`() {
        val selection = StatsDaySelection.startingAt(today)

        assertEquals(today, selection.selected)
        assertEquals(today, selection.weekEnd)
        assertEquals(today.minusDays(6), selection.weekStart)
        assertEquals(6, selection.selectedIndex)
        assertTrue(selection.isSelectedToday(today))
        assertTrue(selection.isCurrentWeek(today))
    }

    // --- Tapping a bar: the core fix ---

    @Test
    fun `selecting a bar moves the selected day to that bar`() {
        val selection = StatsDaySelection.startingAt(today).selectIndex(2, today)

        // index 2 of a window ending today = 4 days before today
        assertEquals(today.minusDays(4), selection.selected)
    }

    @Test
    fun `selecting a bar does not move the window`() {
        val start = StatsDaySelection.startingAt(today)
        val selection = start.selectIndex(0, today)

        assertEquals(start.weekEnd, selection.weekEnd)
        assertEquals(start.weekStart, selection.weekStart)
    }

    @Test
    fun `every bar index round-trips to itself`() {
        val start = StatsDaySelection.startingAt(today)
        for (index in 0 until StatsDaySelection.WINDOW_DAYS) {
            assertEquals(
                "bar $index should stay bar $index",
                index,
                start.selectIndex(index, today).selectedIndex
            )
        }
    }

    @Test
    fun `selecting an out-of-range index clamps into the window instead of throwing`() {
        val start = StatsDaySelection.startingAt(today)

        assertEquals(0, start.selectIndex(-3, today).selectedIndex)
        assertEquals(6, start.selectIndex(99, today).selectedIndex)
    }

    @Test
    fun `a bar in the future is clamped to today`() {
        // A window whose last bar is tomorrow can only arise from a stale state; selecting it
        // must not put the screen on a day that structurally has no data.
        val stale = StatsDaySelection(weekEnd = today.plusDays(1), selected = today)

        assertEquals(today, stale.selectIndex(6, today).selected)
    }

    // --- Arrows ---

    @Test
    fun `previous day steps back inside the window`() {
        val selection = StatsDaySelection.startingAt(today).previousDay()

        assertEquals(today.minusDays(1), selection.selected)
        assertEquals(today, selection.weekEnd)
        assertEquals(5, selection.selectedIndex)
    }

    @Test
    fun `stepping back off the left edge slides the window by exactly one day`() {
        var selection = StatsDaySelection.startingAt(today)
        repeat(6) { selection = selection.previousDay() }
        assertEquals(0, selection.selectedIndex)
        assertEquals(today, selection.weekEnd)

        selection = selection.previousDay()

        assertEquals(today.minusDays(7), selection.selected)
        assertEquals(today.minusDays(1), selection.weekEnd)
        assertEquals(0, selection.selectedIndex)
    }

    @Test
    fun `stepping forward off the right edge slides the window back by one day`() {
        val scrolledBack = StatsDaySelection(
            weekEnd = today.minusDays(3),
            selected = today.minusDays(3)
        )

        val selection = scrolledBack.nextDay(today)

        assertEquals(today.minusDays(2), selection.selected)
        assertEquals(today.minusDays(2), selection.weekEnd)
        assertEquals(6, selection.selectedIndex)
    }

    @Test
    fun `next day is capped at today`() {
        val selection = StatsDaySelection.startingAt(today)

        assertEquals(selection, selection.nextDay(today))
        assertFalse(selection.canGoForward(today))
    }

    @Test
    fun `forward is available as soon as the selection is in the past`() {
        val selection = StatsDaySelection.startingAt(today).previousDay()

        assertTrue(selection.canGoForward(today))
    }

    @Test
    fun `next day from an old selection inside the window keeps the window still`() {
        val selection = StatsDaySelection.startingAt(today).selectIndex(1, today).nextDay(today)

        assertEquals(2, selection.selectedIndex)
        assertEquals(today, selection.weekEnd)
    }

    // --- The way back ---

    @Test
    fun `jump to today resets both the selection and the window`() {
        var selection = StatsDaySelection.startingAt(today)
        repeat(20) { selection = selection.previousDay() }

        val reset = selection.jumpToToday(today)

        assertEquals(StatsDaySelection.startingAt(today), reset)
        assertTrue(reset.isSelectedToday(today))
        assertTrue(reset.isCurrentWeek(today))
    }

    // --- Invariant ---

    @Test
    fun `the selected day is always inside the window it draws`() {
        var selection = StatsDaySelection.startingAt(today)
        val moves = listOf(0, 3, 6, 1) // taps interleaved with arrows
        repeat(15) { step ->
            selection = when (step % 3) {
                0 -> selection.previousDay()
                1 -> selection.selectIndex(moves[step % moves.size], today)
                else -> selection.nextDay(today)
            }
            assertFalse(
                "selected ${selection.selected} escaped [${selection.weekStart}, ${selection.weekEnd}]",
                selection.selected.isBefore(selection.weekStart) ||
                    selection.selected.isAfter(selection.weekEnd)
            )
            assertEquals(
                selection.selected,
                selection.weekStart.plusDays(selection.selectedIndex.toLong())
            )
        }
    }

    @Test
    fun `window is always exactly seven days`() {
        var selection = StatsDaySelection.startingAt(today)
        repeat(10) { selection = selection.previousDay() }

        assertEquals(
            StatsDaySelection.WINDOW_DAYS - 1L,
            java.time.temporal.ChronoUnit.DAYS.between(selection.weekStart, selection.weekEnd)
        )
    }

    // --- Labels ---

    @Test
    fun `day label names today and yesterday in words`() {
        assertEquals("Today", StatsDateLabels.day(today, today))
        assertEquals("Yesterday", StatsDateLabels.day(today.minusDays(1), today))
    }

    @Test
    fun `day label falls back to an explicit date further back`() {
        val label = StatsDateLabels.day(today.minusDays(3), today)

        assertTrue("expected a date, got: $label", label.contains("24"))
        assertTrue("expected a month, got: $label", label.contains("Aug"))
    }

    @Test
    fun `range label is plain english for the current week and explicit once scrolled back`() {
        val current = StatsDaySelection.startingAt(today)
        assertEquals(
            "Last 7 days",
            StatsDateLabels.range(current.weekStart, current.weekEnd, today)
        )

        val past = StatsDaySelection(weekEnd = today.minusDays(2), selected = today.minusDays(2))
        val label = StatsDateLabels.range(past.weekStart, past.weekEnd, today)
        assertTrue("expected explicit dates, got: $label", label.contains("–"))
        assertTrue("expected the window end, got: $label", label.contains("25"))
    }
}
