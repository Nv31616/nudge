package com.astraedus.nudge.ui.screens.stats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for the day-selection contract, in the same spirit as
 * `BlockOverlayWalkAwayContractTest` and `ContentFilterAssetTest`: the defect was not in any
 * VALUE a unit test could inspect, it was in the SHAPE of the code.
 *
 * The bug: `WeeklyBarChart` and `BlockedTrendChart` each held
 * `var selectedIndex by remember { mutableStateOf<Int?>(null) }`. Tapping a bar mutated that
 * private state, dimmed the neighbouring bars and printed a tooltip — and no number anywhere
 * on the screen moved, because the screen's real day lived in the ViewModel. It compiled, it
 * animated, and it was wrong. Compose UI is not JVM-testable here, so nothing else in the
 * suite can catch a regression to that shape.
 *
 * These assertions describe the CLASS of defect: any future edit that gives a day-scoped chart
 * back its own selection state, or that stops feeding a screen's selection into its charts,
 * fails here.
 */
class ChartSelectionContractTest {

    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/$relativePath"), File("app/src/$relativePath"))
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relativePath not found from working dir ${File("").absolutePath}"))
            .readText()
    }

    private val dayScopedCharts = listOf(
        "main/java/com/astraedus/nudge/ui/screens/stats/charts/WeeklyBarChart.kt",
        "main/java/com/astraedus/nudge/ui/screens/stats/charts/BlockedTrendChart.kt"
    )

    private val dayScopedScreens = listOf(
        "main/java/com/astraedus/nudge/ui/screens/stats/StatsScreen.kt",
        "main/java/com/astraedus/nudge/ui/screens/stats/AppDetailScreen.kt"
    )

    private val dayScopedViewModels = listOf(
        "main/java/com/astraedus/nudge/ui/screens/stats/StatsViewModel.kt",
        "main/java/com/astraedus/nudge/ui/screens/stats/AppDetailViewModel.kt"
    )

    /** The exact shape that shipped the bug. */
    @Test
    fun `a day-scoped chart owns no selection state of its own`() {
        dayScopedCharts.forEach { path ->
            val text = source(path)
            assertFalse(
                "$path must not hold its own selection — that is the bug where a tapped bar " +
                    "highlights itself and no number on the screen moves. Take selectedIndex " +
                    "from the caller instead.",
                text.contains("mutableStateOf")
            )
        }
    }

    /** The other half: state hoisted out is useless unless it is passed back in. */
    @Test
    fun `a day-scoped chart is controlled through selectedIndex and onSelectDay`() {
        dayScopedCharts.forEach { path ->
            val text = source(path)
            assertTrue("$path must accept selectedIndex", text.contains("selectedIndex: Int?"))
            assertTrue(
                "$path must accept onSelectDay",
                text.contains("onSelectDay: ((Int) -> Unit)?")
            )
        }
    }

    @Test
    fun `every day-scoped screen wires its charts to the shared day selection`() {
        dayScopedScreens.forEach { path ->
            val text = source(path)
            assertTrue(
                "$path must feed its charts the screen's selected day",
                text.contains("selectedIndex = state.selectedDayIndex")
            )
            assertTrue(
                "$path must push bar taps back into the ViewModel",
                text.contains("onSelectDay = viewModel::selectDay")
            )
        }
    }

    /**
     * The ViewModels must route the day through [StatsDaySelection] rather than a bare
     * `LocalDate`: the bare date is what let the window and the selection drift apart, and the
     * clamping / window-sliding rules only exist inside that type.
     */
    @Test
    fun `day-scoped view models hold a StatsDaySelection, not a loose date`() {
        dayScopedViewModels.forEach { path ->
            val text = source(path)
            assertTrue(
                "$path must hold a StatsDaySelection",
                text.contains("MutableStateFlow(StatsDaySelection.startingAt(")
            )
            assertTrue("$path must expose a bar-tap entry point", text.contains("fun selectDay("))
            assertTrue("$path must offer a way back to today", text.contains("fun jumpToToday("))
        }
    }

    /**
     * The screens' way back. It is rendered conditionally, so a silent removal would look like
     * nothing at all until a user scrolled back a week and could not return.
     */
    @Test
    fun `every day-scoped screen offers a way back to today`() {
        dayScopedScreens.forEach { path ->
            assertTrue(
                "$path must wire the jump-back-to-today action",
                source(path).contains("onJumpToToday = viewModel::jumpToToday")
            )
        }
    }

    /**
     * The home dashboard embeds the same charts read-only. If someone hands them a callback
     * there, taps stop reaching the card and the charts start a day-selection interaction the
     * home screen has nowhere to display.
     */
    @Test
    fun `the home dashboard embeds the charts read-only`() {
        val text = source("main/java/com/astraedus/nudge/ui/screens/home/HomeScreen.kt")
        assertFalse(
            "home charts must stay read-only so taps open the stats screen",
            text.contains("onSelectDay =")
        )
        assertTrue(
            "the home charts card must navigate to the full stats screen",
            text.contains("onClick = onNavigateToStats")
        )
    }
}
