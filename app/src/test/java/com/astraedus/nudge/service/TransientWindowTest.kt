package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for issue #5: keyboards and paste/long-press popups re-triggering the block.
 *
 * The bug: after completing a delay (passthrough granted for app X), a soft keyboard NOT on the
 * hardcoded list (e.g. FUTO), or the `android` framework package that hosts the paste / long-press
 * popup toolbar, surfaced a different package on a window event. Routing that into evaluation
 * cleared X's passthrough, so tapping back into X re-triggered the delay. The fix recognises those
 * windows as transient and ignores their events, keeping the passthrough intact.
 */
class TransientWindowTest {

    private val futo = "org.futo.inputmethod.latin"
    private val gboard = "com.google.android.inputmethod.latin"
    private val instagram = "com.instagram.android"

    // --- isTransientNonAppPackage: the core recognition the fix hinges on ---

    @Test
    fun `the reporter's third-party keyboard (FUTO) is recognised as transient when it is the active IME`() {
        // This is the exact issue #5 scenario: FUTO is not on any hardcoded list, but matching the
        // active default IME dynamically catches it, so its window events no longer clear passthrough.
        assertTrue(
            NudgeAccessibilityService.isTransientNonAppPackage(
                packageName = futo,
                currentImePackage = futo
            )
        )
    }

    @Test
    fun `a hardcoded keyboard is transient even without a dynamic match`() {
        assertTrue(
            NudgeAccessibilityService.isTransientNonAppPackage(
                packageName = gboard,
                currentImePackage = null
            )
        )
    }

    @Test
    fun `the android framework package (paste and long-press popups) is transient`() {
        // Long-press link menus and the text-selection / paste floating toolbar are hosted by the
        // `android` package — the second symptom the reporter found.
        assertTrue(
            NudgeAccessibilityService.isTransientNonAppPackage(
                packageName = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
                currentImePackage = futo
            )
        )
    }

    @Test
    fun `a real app is never transient`() {
        assertFalse(
            NudgeAccessibilityService.isTransientNonAppPackage(
                packageName = instagram,
                currentImePackage = futo
            )
        )
        assertFalse(
            NudgeAccessibilityService.isTransientNonAppPackage(
                packageName = instagram,
                currentImePackage = null
            )
        )
    }

    // --- isOverlayBypassedByForeground: a transient window must not clear an active block overlay ---

    @Test
    fun `active keyboard while overlay up is not an overlay bypass`() {
        assertFalse(
            NudgeAccessibilityService.isOverlayBypassedByForeground(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = futo,
                ownPackageName = "com.astraedus.nudge",
                currentImePackage = futo
            )
        )
    }

    @Test
    fun `android popup while overlay up is not an overlay bypass`() {
        assertFalse(
            NudgeAccessibilityService.isOverlayBypassedByForeground(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
                ownPackageName = "com.astraedus.nudge",
                currentImePackage = futo
            )
        )
    }

    @Test
    fun `a real app returning to foreground is still a bypass (regression guard)`() {
        // The transient exclusions must not weaken the genuine tab-out-and-back-in re-block path.
        assertTrue(
            NudgeAccessibilityService.isOverlayBypassedByForeground(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = instagram,
                ownPackageName = "com.astraedus.nudge",
                currentImePackage = futo
            )
        )
    }
}
