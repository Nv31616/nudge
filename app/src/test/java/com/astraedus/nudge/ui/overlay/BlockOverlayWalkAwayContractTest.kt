package com.astraedus.nudge.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for the walk-away path in `BlockOverlayActivity`.
 *
 * The activity itself is not JVM-testable (Android lifecycle, Hilt field injection, Compose), and
 * the bug it shipped was not in any *value* a unit test could inspect — it was in the SHAPE of the
 * code: a stat written from an unowned `CoroutineScope(Dispatchers.IO).launch { … }` on an activity
 * that finishes microseconds later, and a `startActivity(HOME)` racing that same `finish()`. The
 * repo already tests shape where behaviour is untestable (`ContentFilterAssetTest` tests the
 * shipped asset; `NudgeDatabaseMigrationTest` reflects over the entity), so this does the same.
 *
 * These assertions describe the CLASS of defect, not one instance: any future edit that reaches for
 * a throwaway coroutine scope to write a stat, or that hand-rolls the go-home intent instead of the
 * accessibility service's `GLOBAL_ACTION_HOME`, fails here.
 */
class BlockOverlayWalkAwayContractTest {

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/java/com/astraedus/nudge/ui/overlay/BlockOverlayActivity.kt"),
            File("app/src/main/java/com/astraedus/nudge/ui/overlay/BlockOverlayActivity.kt")
        )
        (candidates.firstOrNull { it.exists() }
            ?: error("BlockOverlayActivity.kt not found from working dir ${File("").absolutePath}"))
            .readText()
    }

    /**
     * The whole reason the walk-away could go missing: the write was owned by nothing. A
     * process-lifetime `RecordWalkAwayUseCase` replaces it, so the row does not depend on the
     * activity still being alive when the insert runs.
     */
    @Test
    fun `the walk-away is written through the use case, not an ad-hoc coroutine scope`() {
        assertTrue(
            "walk-away must go through RecordWalkAwayUseCase",
            source.contains("recordWalkAway.record(")
        )
        assertFalse(
            "no throwaway CoroutineScope(...) may own a stat write on a finishing activity",
            source.contains("CoroutineScope(")
        )
    }

    /**
     * The backlog item this closes: "'I changed my mind' can leave the user inside the blocked app".
     * `finish()` on this singleInstance activity pops back to the blocked app's task, so a bare
     * `startActivity(HOME)` was in a race it could lose. `GLOBAL_ACTION_HOME` is dispatched by the
     * system and does not race us — it is already how EmergencyPassManager, AutoKickExecutor and
     * StrictModeGuardActivity leave an app.
     */
    @Test
    fun `going home prefers the accessibility service global action, with an intent fallback`() {
        assertTrue(
            "walk-away must prefer NudgeAccessibilityService.requestGoHome()",
            source.contains("NudgeAccessibilityService.requestGoHome()")
        )
        assertTrue(
            "a HOME intent must remain as the fallback when the service is not connected",
            source.contains("Intent.CATEGORY_HOME")
        )
    }

    /**
     * `navigateHome` is reached from the overlay button AND from the back button, so it needs a
     * once-only gate or one attempt could log two walk-aways and overstate the tile.
     */
    @Test
    fun `the walk-away path is gated so one attempt cannot log two events`() {
        assertTrue(
            "navigateHome must claim a once-only flag before recording",
            source.contains("walkedAway.compareAndSet(false, true)")
        )
    }

    /**
     * Walking away is NOT permission to enter. Granting passthrough here would open the app the user
     * just declined to open; only `onTimerComplete` (they waited it out) may grant.
     */
    @Test
    fun `walking away never grants passthrough`() {
        val navigateHome = source.substringAfter("private fun navigateHome()").substringBefore("\n    }")
        assertFalse(
            "walking away must not grant passthrough",
            navigateHome.contains("passthroughManager.grant")
        )
    }
}
