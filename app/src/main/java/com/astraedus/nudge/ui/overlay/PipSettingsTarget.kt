package com.astraedus.nudge.ui.overlay

/** One candidate destination for "let me turn picture-in-picture off for this app". */
data class PipSettingsTarget(val action: String, val usePackageUri: Boolean)

/**
 * Ranked candidates for deep-linking the user to the picture-in-picture settings that let them
 * turn PiP off for one app (see [PipEscapeActivity] / issue #19).
 *
 * `android.settings.PICTURE_IN_PICTURE_SETTINGS` is NOT a public SDK constant — it is only a raw
 * action string that happens to resolve on AOSP/Pixel builds, so it must be treated as best-effort
 * rather than guaranteed. Whether it resolves at all, and whether a package `Uri` narrows it to the
 * per-app toggle or falls back to the app list, both vary by OEM and Android version. Firing an
 * unresolvable intent throws [android.content.ActivityNotFoundException] straight at the user, and
 * this whole screen exists to hand them a working deep link — so resolution MUST be checked at
 * runtime (see [firstResolvable]) and the caller must degrade to manual instructions instead of
 * crashing when nothing resolves.
 */
object PipSettings {
    const val ACTION_PICTURE_IN_PICTURE_SETTINGS = "android.settings.PICTURE_IN_PICTURE_SETTINGS"
    const val ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"

    /**
     * Ordered most-specific-first: the per-app PiP toggle (what actually solves issue #19), then
     * the un-scoped PiP app list, then the generic App Info page as a last-resort fallback that is
     * guaranteed to exist on every Android device.
     */
    fun targets(): List<PipSettingsTarget> = listOf(
        PipSettingsTarget(ACTION_PICTURE_IN_PICTURE_SETTINGS, usePackageUri = true),
        PipSettingsTarget(ACTION_PICTURE_IN_PICTURE_SETTINGS, usePackageUri = false),
        PipSettingsTarget(ACTION_APPLICATION_DETAILS_SETTINGS, usePackageUri = true)
    )

    /** First target the device can actually open, or null when none resolve. */
    fun firstResolvable(resolves: (PipSettingsTarget) -> Boolean): PipSettingsTarget? =
        targets().firstOrNull(resolves)
}
