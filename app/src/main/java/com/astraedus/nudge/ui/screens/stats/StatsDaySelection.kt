package com.astraedus.nudge.ui.screens.stats

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The ONE answer to "which day is the user looking at" on the day-scoped stats screens
 * (Usage Stats, App Detail).
 *
 * Before this existed the question had two answers: the screen's date arrows moved a
 * `LocalDate` in the ViewModel, while the bar charts each kept a private `selectedIndex`
 * that only dimmed bars and printed a tooltip. Tapping a bar therefore *looked* like it
 * selected a day and changed no number on the screen. Charts are now controlled by this
 * type, so a highlighted bar and the numbers under it cannot disagree.
 *
 * Two fields, one invariant: [selected] always lies inside the 7-day window ending at
 * [weekEnd]. Tapping a bar moves [selected] only — the window stays put, so bars never
 * slide out from under the finger. The arrows move [selected] and slide the window by the
 * minimum needed to keep it visible.
 *
 * Pure `java.time`; no Android, no Compose state. Fully JVM-tested in `StatsDaySelectionTest`.
 */
@Immutable
data class StatsDaySelection(
    /** Last (rightmost) day of the 7-bar window. */
    val weekEnd: LocalDate,
    /** The day whose numbers the screen is showing. Always within `[weekStart, weekEnd]`. */
    val selected: LocalDate
) {

    /** First (leftmost) day of the 7-bar window. */
    val weekStart: LocalDate get() = weekEnd.minusDays((WINDOW_DAYS - 1).toLong())

    /**
     * Index of [selected] among the 7 bars: 0 = [weekStart] (oldest), 6 = [weekEnd].
     * Coerced rather than allowed to go out of range — a selection outside the window is a
     * bug in a transition, and clamping shows the user the nearest real bar instead of
     * crashing or silently highlighting nothing.
     */
    val selectedIndex: Int
        get() = (WINDOW_DAYS - 1 - ChronoUnit.DAYS.between(selected, weekEnd))
            .toInt()
            .coerceIn(0, WINDOW_DAYS - 1)

    fun isSelectedToday(today: LocalDate): Boolean = selected == today

    /** Whether the window shown is the current one (i.e. its last bar is today). */
    fun isCurrentWeek(today: LocalDate): Boolean = weekEnd == today

    /** False on today — there is no data for tomorrow, so the arrow must be disabled. */
    fun canGoForward(today: LocalDate): Boolean = selected.isBefore(today)

    fun previousDay(): StatsDaySelection = withSelected(selected.minusDays(1))

    fun nextDay(today: LocalDate): StatsDaySelection =
        if (canGoForward(today)) withSelected(selected.plusDays(1)) else this

    /**
     * Select the day drawn at bar [index]. The window does not move.
     *
     * [index] is clamped to the window and the resulting day is clamped to [today]: a bar can
     * only be in the future if the window itself is stale, and selecting a future day would
     * show a screen of structural zeroes.
     */
    fun selectIndex(index: Int, today: LocalDate): StatsDaySelection {
        val target = weekStart.plusDays(index.coerceIn(0, WINDOW_DAYS - 1).toLong())
        return withSelected(if (target.isAfter(today)) today else target)
    }

    /** The way back. Resets both the window and the selection to today. */
    fun jumpToToday(today: LocalDate): StatsDaySelection = startingAt(today)

    /** Moves [selected], sliding the window by the minimum needed to keep it visible. */
    private fun withSelected(next: LocalDate): StatsDaySelection = when {
        next.isAfter(weekEnd) -> StatsDaySelection(weekEnd = next, selected = next)
        next.isBefore(weekStart) ->
            StatsDaySelection(weekEnd = next.plusDays((WINDOW_DAYS - 1).toLong()), selected = next)
        else -> copy(selected = next)
    }

    companion object {
        /** Bars in the window. The charts, the calculator and this type must agree on it. */
        const val WINDOW_DAYS = 7

        fun startingAt(today: LocalDate): StatsDaySelection =
            StatsDaySelection(weekEnd = today, selected = today)
    }
}

/**
 * Date wording shared by the stats screens, so a day is never called "Today" in one place and
 * printed as a date in another.
 */
object StatsDateLabels {

    /** "Today" / "Yesterday" / "Thu, Aug 21". */
    fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> full(date)
    }

    /** Always the explicit date: "Thu, Aug 21". */
    fun full(date: LocalDate): String {
        val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        return "$dayOfWeek, $month ${date.dayOfMonth}"
    }

    /**
     * Label for the 7-day window a chart is drawing. The current window gets the plain-English
     * "Last 7 days"; a scrolled-back window gets explicit dates, because that is exactly when
     * the user needs to know they are not looking at now.
     */
    fun range(start: LocalDate, end: LocalDate, today: LocalDate): String =
        if (end == today) "Last 7 days" else "${shortDate(start)} – ${shortDate(end)}"

    private fun shortDate(date: LocalDate): String {
        val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        return "$month ${date.dayOfMonth}"
    }
}
