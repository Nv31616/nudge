package com.astraedus.nudge.service

/**
 * One window, reduced to what picture-in-picture detection cares about.
 *
 * [isApplicationWindow] is load-bearing, not decoration — see [PipWindowProbe.pipPackages].
 *
 * [packageName] is null for a window whose owner we did not resolve. The service's reader
 * deliberately only resolves the owner of windows that are ALREADY flagged as PiP, because
 * resolving it means reading that window's root node — a binder call per window.
 */
data class PipWindow(
    val packageName: String?,
    val isPictureInPicture: Boolean,
    val isApplicationWindow: Boolean
)

/**
 * Answers "which packages are currently playing in a picture-in-picture window?" for issue #19,
 * with a short result cache so the underlying window read cannot be hammered.
 *
 * **Why this exists.** When Nudge's block overlay backgrounds an app, the app can enter PiP and keep
 * playing in a floating always-on-top window. Our overlay is fullscreen and is the top resumed
 * activity and still loses — platform behaviour, not an overlay bug. Detecting it is the only thing
 * Nudge can do in code (the remedy is the per-app PiP permission in Settings), and the accessibility
 * window list is the only place the state is visible.
 *
 * **Cost discipline.** Reading the window list is a binder call. The service refreshes only on
 * window-change events (a PiP window can only appear or vanish via one) and caches the derived
 * answer for every other event; this class adds the second line of defence, a [throttleMs] window
 * during which repeated questions reuse the previous answer.
 */
internal class PipWindowProbe(
    private val throttleMs: Long = DEFAULT_THROTTLE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readWindows: () -> List<PipWindow>
) {

    private var lastReadAt: Long = Long.MIN_VALUE
    private var cached: Set<String> = emptySet()

    /**
     * Packages owning a picture-in-picture window right now; empty when nothing is in PiP (or the
     * window list could not be read). Re-reads at most once per [throttleMs].
     */
    fun packagesInPictureInPicture(): Set<String> {
        val now = clock()
        // Long.MIN_VALUE sentinel: the subtraction would overflow, so compare the timestamps
        // instead. Without this the very first question at clock 0 reports a cache hit and the
        // probe answers "nothing in PiP" forever.
        if (lastReadAt != Long.MIN_VALUE && now - lastReadAt < throttleMs) return cached
        lastReadAt = now
        cached = pipPackages(readWindows())
        return cached
    }

    companion object {
        /**
         * Matches the cadence of the other bounded reads on this path (the issue #7 active-window
         * check uses the same 500ms): short enough that a PiP escape is caught on the first event
         * burst that follows it, long enough that a stream of window events cannot turn into a
         * stream of binder calls.
         */
        const val DEFAULT_THROTTLE_MS: Long = 500L

        /**
         * Owners of every APPLICATION window currently in picture-in-picture.
         *
         * **The `isApplicationWindow` filter is the fix for the v1.12.0 field failure, do not drop
         * it.** On a live Pixel 3 / API 31 bubble, `dumpsys accessibility` shows TWO windows with
         * `pictureInPicture=true`:
         *
         * ```
         * title=Picture-in-Picture menu, type=TYPE_SYSTEM,      layer=2, pictureInPicture=true
         * title=YouTube,                 type=TYPE_APPLICATION, layer=1, pictureInPicture=true
         * ```
         *
         * SystemUI's PiP *menu* is flagged PiP too, and it sorts BEFORE the app's own window. The
         * first implementation took the first flagged window it found, resolved its owner to
         * `com.android.systemui`, compared that against the blocked package, got no match, and
         * returned silently — so detection never fired once in the field and logged nothing to say
         * why. Only the application window identifies the app that is actually escaping.
         *
         * Returns a SET rather than a single package: "is P in PiP?" is the question every caller
         * actually has, and answering it by set membership cannot be broken by another PiP-flagged
         * window sorting first.
         *
         * A PiP window whose owner could not be resolved is skipped rather than guessed at: callers
         * compare against packages they are blocking, and a wrong match would suppress a real block.
         */
        fun pipPackages(windows: List<PipWindow>): Set<String> = windows
            .asSequence()
            .filter { it.isPictureInPicture && it.isApplicationWindow }
            .mapNotNull { it.packageName?.takeIf(String::isNotBlank) }
            .toSet()

        /**
         * Of [pipPackages], those that are NOT the foreground app — i.e. packages whose only
         * presence on screen is a floating PiP window.
         *
         * This is the distinction the whole fix turns on. An accessibility event carrying package P
         * normally means "P is in front", and every evaluation path in the service assumes it. A PiP
         * window breaks that assumption: P has a window and fires events while the user is somewhere
         * else entirely. Treating those as foreground entries is what re-blocked YouTube nine times
         * in five minutes and inflated the all-time Blocked count by eleven during a single incident
         * — while the tester was navigating inside Nudge itself.
         *
         * @param activeWindowPackage owner of the active window, or null when it could not be read.
         *   Null means every PiP package counts as PiP-only, which is the safe reading: a window in
         *   PiP is by definition not the fullscreen foreground app, and the active-window check is
         *   only a guard for the brief transition when the user expands the bubble back to
         *   fullscreen. A missed block here self-corrects the moment PiP ends.
         */
        fun pipOnlyPackages(pipPackages: Set<String>, activeWindowPackage: String?): Set<String> =
            if (activeWindowPackage == null) pipPackages else pipPackages - activeWindowPackage
    }
}
