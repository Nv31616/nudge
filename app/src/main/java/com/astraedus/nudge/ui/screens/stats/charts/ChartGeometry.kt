package com.astraedus.nudge.ui.screens.stats.charts

/**
 * Bar layout arithmetic shared by every evenly-spaced bar chart in the stats screens.
 *
 * It used to be copy-pasted into each chart's `Canvas` draw block AND, separately, into its
 * `detectTapGestures` handler — so a chart could (and did) hit-test against a layout it was
 * not drawing. One source of truth, and it is pure, so the tap mapping is unit-tested
 * instead of eyeballed on a device.
 */
object ChartGeometry {

    /** Width of a single bar when [barCount] bars share [totalWidth] with [spacing] between them. */
    fun barWidth(totalWidth: Float, barCount: Int, spacing: Float): Float {
        if (barCount <= 0) return 0f
        return (totalWidth - (barCount - 1) * spacing) / barCount
    }

    /** Left edge of bar [index]. */
    fun barLeft(index: Int, totalWidth: Float, barCount: Int, spacing: Float): Float =
        index * (barWidth(totalWidth, barCount, spacing) + spacing)

    /**
     * Index of the bar under [x], or -1 when there are no bars.
     *
     * A tap in the gap between two bars resolves to the bar on its left rather than being
     * swallowed: on a phone the gaps are wider than the finger's precision, and "nothing
     * happened" is the worst possible answer to a deliberate tap.
     */
    fun barIndexAt(x: Float, totalWidth: Float, barCount: Int, spacing: Float): Int {
        if (barCount <= 0) return -1
        val step = barWidth(totalWidth, barCount, spacing) + spacing
        if (step <= 0f) return 0
        return (x / step).toInt().coerceIn(0, barCount - 1)
    }
}
