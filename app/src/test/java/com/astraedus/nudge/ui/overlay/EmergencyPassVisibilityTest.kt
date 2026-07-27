package com.astraedus.nudge.ui.overlay

import com.astraedus.nudge.domain.emergency.EmergencyPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visibility contract for the daily-pass action on block overlays (v1.10.0).
 *
 * Headline invariant: **Strict Mode is not an input.** [resolveEmergencyPassState] takes no
 * strict-mode flag at all, so the commitment lock can no longer silently revoke an escape hatch the
 * user deliberately left on — it bites in Settings instead, on the OFF→ON flip
 * (`SettingsWeakeningTest`). What is left here is the user's own toggle plus the global 24h lockout,
 * and nothing else. A regression that re-introduces strict-mode hiding would have to add the
 * parameter back, which these tests would not compile against.
 */
class EmergencyPassVisibilityTest {

    private val now = 1_700_000_000_000L
    private val pkg = "com.instagram.android"
    private val lockout = EmergencyPass.LOCKOUT_MS

    /** Pass never used: nothing in the ledger. */
    private val unused = emptyMap<String, Long>()

    /** Pass used one hour ago on some app — well inside the 24h global lockout. */
    private val spent = mapOf(EmergencyPass.GLOBAL_KEY to now - 3_600_000L)

    @Test
    fun `toggle on and pass unused shows the usable action`() {
        val state = resolveEmergencyPassState(pkg, passEnabled = true, usage = unused, now = now)

        assertTrue("the pass should be offered", state.canUse)
        assertFalse("cannot be both usable and locked", state.locked)
        assertEquals(0L, state.nextPassMs)
    }

    @Test
    fun `toggle off hides the action entirely even with the pass unused`() {
        val state = resolveEmergencyPassState(pkg, passEnabled = false, usage = unused, now = now)

        assertFalse(state.canUse)
        assertFalse("the toggle alone must hide it — no greyed hint either", state.locked)
    }

    @Test
    fun `spent pass shows the greyed locked hint with the remaining lockout`() {
        val state = resolveEmergencyPassState(pkg, passEnabled = true, usage = spent, now = now)

        assertFalse(state.canUse)
        assertTrue("a spent pass stays visible but disabled", state.locked)
        assertEquals(lockout - 3_600_000L, state.nextPassMs)
    }

    @Test
    fun `spent pass with the toggle off stays hidden`() {
        val state = resolveEmergencyPassState(pkg, passEnabled = false, usage = spent, now = now)

        assertFalse(state.canUse)
        assertFalse(state.locked)
        assertEquals(0L, state.nextPassMs)
    }

    @Test
    fun `the action becomes usable again exactly when the lockout elapses`() {
        val usedAt = now - lockout
        val state = resolveEmergencyPassState(
            pkg, passEnabled = true, usage = mapOf(EmergencyPass.GLOBAL_KEY to usedAt), now = now
        )

        assertTrue("24h elapsed → available again", state.canUse)
        assertFalse(state.locked)
    }

    @Test
    fun `one millisecond before the lockout elapses the action is still locked`() {
        val usedAt = now - lockout + 1
        val state = resolveEmergencyPassState(
            pkg, passEnabled = true, usage = mapOf(EmergencyPass.GLOBAL_KEY to usedAt), now = now
        )

        assertFalse(state.canUse)
        assertTrue(state.locked)
        assertEquals(1L, state.nextPassMs)
    }

    @Test
    fun `lockout is global — a legacy per-app ledger still locks a different app`() {
        val legacyPerApp = mapOf(
            "com.zhiliaoapp.musically" to now - lockout - 5_000L, // long expired
            "com.google.android.youtube" to now - 60_000L         // most recent use wins
        )

        val state = resolveEmergencyPassState(pkg, passEnabled = true, usage = legacyPerApp, now = now)

        assertFalse("the most recent use on ANY app locks every app", state.canUse)
        assertTrue(state.locked)
    }

    @Test
    fun `content filter pseudo package never shows the action`() {
        val state = resolveEmergencyPassState("web", passEnabled = true, usage = unused, now = now)

        assertFalse("there is no real app to grant a window on", state.canUse)
        assertFalse(state.locked)
    }

    @Test
    fun `blank package never shows the action`() {
        assertEquals(
            EmergencyPassUiState(),
            resolveEmergencyPassState("", passEnabled = true, usage = unused, now = now)
        )
        assertEquals(
            EmergencyPassUiState(),
            resolveEmergencyPassState("   ", passEnabled = true, usage = unused, now = now)
        )
    }

    @Test
    fun `malformed persisted ledger fails soft to available`() {
        // EmergencyPass.parse never throws; garbage → empty map → pass available. The overlay hot
        // path must not be able to crash (or hard-lock the user out) on corrupt prefs.
        val state = resolveEmergencyPassState(
            pkg, passEnabled = true, usage = EmergencyPass.parse("not;a=valid;ledger"), now = now
        )

        assertTrue(state.canUse)
    }
}
