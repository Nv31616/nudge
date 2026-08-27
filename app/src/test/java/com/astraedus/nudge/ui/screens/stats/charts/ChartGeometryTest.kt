package com.astraedus.nudge.ui.screens.stats.charts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tap-to-bar mapping used to be copy-pasted into each chart's gesture handler while the
 * draw block computed its own layout — nothing forced the two to agree, and nothing tested
 * either. This pins the arithmetic both now share.
 */
class ChartGeometryTest {

    private val width = 700f
    private val spacing = 10f
    private val count = 7

    // 7 bars, 6 gaps of 10 = 60; (700 - 60) / 7 = 91.43 per bar, step = 101.43

    @Test
    fun `bar width accounts for the gaps between bars`() {
        assertEquals(
            (700f - 6 * 10f) / 7f,
            ChartGeometry.barWidth(width, count, spacing),
            0.001f
        )
    }

    @Test
    fun `bar left edges are evenly stepped and the last bar fits`() {
        val barWidth = ChartGeometry.barWidth(width, count, spacing)
        assertEquals(0f, ChartGeometry.barLeft(0, width, count, spacing), 0.001f)
        assertEquals(
            width,
            ChartGeometry.barLeft(count - 1, width, count, spacing) + barWidth,
            0.01f
        )
    }

    @Test
    fun `a tap on a bar resolves to that bar`() {
        for (index in 0 until count) {
            val centre = ChartGeometry.barLeft(index, width, count, spacing) +
                ChartGeometry.barWidth(width, count, spacing) / 2f
            assertEquals(index, ChartGeometry.barIndexAt(centre, width, count, spacing))
        }
    }

    @Test
    fun `a tap on the far left and far right edges resolve to the first and last bars`() {
        assertEquals(0, ChartGeometry.barIndexAt(0f, width, count, spacing))
        assertEquals(count - 1, ChartGeometry.barIndexAt(width - 1f, width, count, spacing))
    }

    @Test
    fun `a tap in the gap falls to the bar on its left rather than being swallowed`() {
        val barWidth = ChartGeometry.barWidth(width, count, spacing)
        val inGap = ChartGeometry.barLeft(2, width, count, spacing) + barWidth + spacing / 2f

        assertEquals(2, ChartGeometry.barIndexAt(inGap, width, count, spacing))
    }

    @Test
    fun `out of range taps clamp instead of returning an index that would crash a lookup`() {
        assertEquals(0, ChartGeometry.barIndexAt(-50f, width, count, spacing))
        assertEquals(count - 1, ChartGeometry.barIndexAt(width * 3, width, count, spacing))
    }

    @Test
    fun `no bars yields no index`() {
        assertEquals(-1, ChartGeometry.barIndexAt(10f, width, 0, spacing))
        assertEquals(0f, ChartGeometry.barWidth(width, 0, spacing), 0.001f)
    }

    @Test
    fun `a dense chart with tight spacing still maps every bar distinctly`() {
        // The 24-bar hourly chart uses 2dp spacing.
        val hours = 24
        val tight = 5f
        val seen = (0 until hours).map { index ->
            val centre = ChartGeometry.barLeft(index, width, hours, tight) +
                ChartGeometry.barWidth(width, hours, tight) / 2f
            ChartGeometry.barIndexAt(centre, width, hours, tight)
        }
        assertEquals((0 until hours).toList(), seen)
    }
}
