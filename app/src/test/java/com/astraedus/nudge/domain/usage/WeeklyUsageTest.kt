package com.astraedus.nudge.domain.usage

import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.ui.screens.stats.StatsDaySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The value the day-scoped stats screens read. Its whole job is that the bars and the numbers
 * under them are the SAME data, so the accessors that hand out a day matter as much as the
 * arithmetic that filled it.
 */
class WeeklyUsageTest {

    private val chrome = "com.android.chrome"
    private val youtube = "com.google.android.youtube"

    private val dayMs = 24L * 60L * 60L * 1000L
    private val hourMs = 60L * 60L * 1000L

    /** Realistic spacing, because day lookup is distance-based. */
    private val dayStarts = (0L until 7L).map { 1_700_000_000_000L + it * dayMs }

    private val usage = WeeklyUsage(
        dayStartsMs = dayStarts,
        perDayPerApp = listOf(
            emptyMap(),
            mapOf(chrome to 10L),
            mapOf(chrome to 20L, youtube to 5L),
            emptyMap(),
            mapOf(youtube to 7L),
            emptyMap(),
            mapOf(chrome to 1L, youtube to 2L)
        )
    )

    @Test
    fun `daily totals sum each day across apps`() {
        assertEquals(listOf(0L, 10L, 25L, 0L, 7L, 0L, 3L), usage.dailyTotals())
    }

    @Test
    fun `a single app's series is one column`() {
        assertEquals(listOf(0L, 10L, 20L, 0L, 0L, 0L, 1L), usage.dailyTotalsFor(chrome))
        assertEquals(listOf(0L, 0L, 5L, 0L, 7L, 0L, 2L), usage.dailyTotalsFor(youtube))
    }

    @Test
    fun `an app with no time anywhere reads as zeros, not an empty list`() {
        assertEquals(List(7) { 0L }, usage.dailyTotalsFor("com.unknown.app"))
    }

    /** The invariant the drill-down leans on: the hero total is the sum of the list beneath it. */
    @Test
    fun `a day's total is the sum of that day's per-app list`() {
        dayStarts.forEach { dayStart ->
            assertEquals(usage.perAppOn(dayStart).values.sum(), usage.totalOn(dayStart))
        }
        assertEquals(usage.dailyTotals(), dayStarts.map { usage.totalOn(it) })
    }

    @Test
    fun `a day is addressed by its start timestamp`() {
        assertEquals(mapOf(chrome to 20L, youtube to 5L), usage.perAppOn(dayStarts[2]))
        assertEquals(25L, usage.totalOn(dayStarts[2]))
    }

    /**
     * A screen holds its selection separately from the window it has loaded, so for the frame
     * after an arrow tap it can ask for a day this value does not hold. Empty is the honest
     * answer — returning a NEIGHBOURING day would print one day's numbers under another day's
     * heading, which is the exact defect this type exists to prevent.
     */
    @Test
    fun `a day outside the loaded window reads empty, never a neighbour`() {
        assertEquals(emptyMap<String, Long>(), usage.perAppOn(dayStarts.first() - dayMs))
        assertEquals(emptyMap<String, Long>(), usage.perAppOn(dayStarts.last() + dayMs))
        assertEquals(0L, usage.totalOn(dayStarts.last() + dayMs))
    }

    /**
     * The caller's day start comes from `java.time`, these come from `Calendar`, and in a zone
     * whose DST transition lands at midnight the two can normalise a non-existent local midnight
     * differently. An exact-match lookup that missed would blank a real day — the exact symptom
     * this whole change exists to remove — so the day is matched to the nearest start.
     */
    @Test
    fun `a day start off by a DST normalisation hour still finds its day`() {
        assertEquals(usage.perAppOn(dayStarts[2]), usage.perAppOn(dayStarts[2] + hourMs))
        assertEquals(usage.perAppOn(dayStarts[2]), usage.perAppOn(dayStarts[2] - hourMs))
        assertEquals(25L, usage.totalOn(dayStarts[2] + hourMs))
    }

    /** ...and the slack must never be wide enough to reach the day next door. */
    @Test
    fun `the tolerance cannot reach a neighbouring day`() {
        assertEquals(usage.perAppOn(dayStarts[1]), usage.perAppOn(dayStarts[2] - dayMs))
        assertEquals(10L, usage.totalOn(dayStarts[2] - dayMs))
        assertEquals(7L, usage.totalOn(dayStarts[2] + 2 * dayMs))
    }

    @Test
    fun `an index outside the window reads empty rather than throwing`() {
        assertEquals(emptyMap<String, Long>(), usage.perApp(-1))
        assertEquals(emptyMap<String, Long>(), usage.perApp(7))
    }

    @Test
    fun `the window's own edges are what the screens word it from`() {
        assertEquals(dayStarts.first(), usage.firstDayStartMs)
        assertEquals(dayStarts.last(), usage.lastDayStartMs)
    }

    /** The stats screens read both edges every frame; a degenerate window must not crash them. */
    @Test
    fun `an empty window has edges rather than throwing`() {
        val nothing = WeeklyUsage(emptyList(), emptyList())

        assertEquals(0L, nothing.firstDayStartMs)
        assertEquals(0L, nothing.lastDayStartMs)
        assertEquals(emptyList<Long>(), nothing.dailyTotals())
        assertEquals(emptyMap<String, Long>(), nothing.perAppOn(dayStarts[0]))
    }

    @Test
    fun `an empty window keeps its shape`() {
        val empty = WeeklyUsage.empty(dayStarts)

        assertEquals(7, empty.days)
        assertEquals(List(7) { 0L }, empty.dailyTotals())
        assertEquals(List(7) { 0L }, empty.dailyTotalsFor(chrome))
        assertTrue(empty.perAppOn(dayStarts[3]).isEmpty())
        assertEquals(dayStarts.last(), empty.lastDayStartMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `day starts and day buckets must line up`() {
        WeeklyUsage(dayStarts, listOf(emptyMap(), emptyMap()))
    }

    /**
     * The screens index into this value by the bar the user tapped, so a window that is not the
     * width of the chart would hand back somebody else's day.
     */
    @Test
    fun `the provider's window is exactly as wide as the chart`() {
        assertEquals(StatsDaySelection.WINDOW_DAYS, ScreenTimeProvider.WEEK_DAYS)
    }
}
