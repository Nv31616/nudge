package com.astraedus.nudge.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for issue #19: the picture-in-picture settings deep link.
 *
 * `android.settings.PICTURE_IN_PICTURE_SETTINGS` is not a public SDK constant, so it only exists
 * here as a raw string — a typo would silently no-op instead of failing to compile, which is what
 * the exact-literal test below guards against. Resolution must also be checked at runtime rather
 * than assumed, because OEM/AOSP builds differ on which of the three candidate intents actually
 * resolve; [PipSettings.firstResolvable] is what lets the UI degrade to manual instructions
 * instead of firing an intent that throws [android.content.ActivityNotFoundException] in the
 * user's face.
 */
class PipSettingsTargetTest {

    @Test
    fun `the per-app package-scoped PiP target is tried first`() {
        val ordered = PipSettings.targets()

        assertEquals(
            PipSettingsTarget(PipSettings.ACTION_PICTURE_IN_PICTURE_SETTINGS, usePackageUri = true),
            ordered.first()
        )
    }

    @Test
    fun `falls back to the un-scoped PiP list when only that resolves`() {
        val unscopedPipList =
            PipSettingsTarget(PipSettings.ACTION_PICTURE_IN_PICTURE_SETTINGS, usePackageUri = false)

        val resolved = PipSettings.firstResolvable { candidate -> candidate == unscopedPipList }

        assertEquals(unscopedPipList, resolved)
    }

    @Test
    fun `falls back to app details when neither PiP target resolves`() {
        val appDetails =
            PipSettingsTarget(PipSettings.ACTION_APPLICATION_DETAILS_SETTINGS, usePackageUri = true)

        val resolved = PipSettings.firstResolvable { candidate -> candidate == appDetails }

        assertEquals(appDetails, resolved)
    }

    @Test
    fun `returns null when nothing resolves so the UI can degrade instead of throwing`() {
        val resolved = PipSettings.firstResolvable { false }

        assertNull(resolved)
    }

    @Test
    fun `the action strings are the exact literals the platform expects`() {
        assertEquals(
            "android.settings.PICTURE_IN_PICTURE_SETTINGS",
            PipSettings.ACTION_PICTURE_IN_PICTURE_SETTINGS
        )
        assertEquals(
            "android.settings.APPLICATION_DETAILS_SETTINGS",
            PipSettings.ACTION_APPLICATION_DETAILS_SETTINGS
        )
    }
}
