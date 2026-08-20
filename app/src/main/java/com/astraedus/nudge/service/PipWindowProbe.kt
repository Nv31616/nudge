package com.astraedus.nudge.service

/**
 * One window, reduced to the two things picture-in-picture escape detection cares about.
 *
 * [packageName] is null for a window whose owner we did not resolve. The service's reader
 * deliberately only resolves the owner of windows that are ALREADY flagged as PiP, because
 * resolving it means reading that window's root node — a binder call per window. Every non-PiP
 * window therefore arrives here with a null package, and [PipWindowProbe.pipPackage] ignores it.
 */
data class PipWindow(val packageName: String?, val isPictureInPicture: Boolean)

/**
 * Answers "is some app currently playing in a picture-in-picture window, and which one?" for issue
 * #19, with a short result cache so the underlying window read cannot be hammered.
 *
 * **Why this exists.** When Nudge's block overlay backgrounds an app, the app can enter PiP and keep
 * playing in a floating always-on-top window. Our overlay is fullscreen and is the top resumed
 * activity and still loses — that is platform behaviour, not an overlay bug. Detecting it is the
 * only thing Nudge can do in code (the actual remedy is the per-app PiP permission in Settings), and
 * the accessibility window list is the only place the state is visible.
 *
 * **Cost discipline.** Reading the window list is a binder call, and it sits on a branch of
 * `onAccessibilityEvent`. The caller applies the cheap rejections first (wrong event type, no live
 * block, event package is not the blocked app); this class adds the second line of defence, a
 * [throttleMs] window during which repeated questions reuse the previous answer.
 *
 * **Why caching the answer is safe in both directions.** A stale `true` (PiP already closed) means
 * the caller keeps the block asserted for up to [throttleMs] longer — the fail-safe direction. A
 * stale `false` (PiP just opened) means the escape is noticed on the next event a fraction of a
 * second later. Neither can produce a wrong permanent state.
 */
internal class PipWindowProbe(
    private val throttleMs: Long = DEFAULT_THROTTLE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readWindows: () -> List<PipWindow>
) {

    private var lastReadAt: Long = Long.MIN_VALUE
    private var cached: String? = null

    /**
     * Package owning a picture-in-picture window right now, or null when nothing is in PiP (or the
     * window list could not be read). Re-reads at most once per [throttleMs].
     */
    fun packageInPictureInPicture(): String? {
        val now = clock()
        // Long.MIN_VALUE start: the subtraction would overflow, so compare the timestamps instead.
        if (lastReadAt != Long.MIN_VALUE && now - lastReadAt < throttleMs) return cached
        lastReadAt = now
        cached = pipPackage(readWindows())
        return cached
    }

    companion object {
        /**
         * Matches the cadence of the other bounded reads on this path (the issue #7 active-window
         * check uses the same 500ms): short enough that a PiP escape is still caught on the first
         * event burst that follows it, long enough that a stream of window events cannot turn into a
         * stream of binder calls.
         */
        const val DEFAULT_THROTTLE_MS: Long = 500L

        /**
         * The first window that is both in PiP and has a resolved owner. Pure, so the selection rule
         * is unit-testable without an Android window list.
         *
         * A PiP window whose owner could not be resolved is skipped rather than guessed at: the
         * caller compares the result against the package it is actively blocking, and a wrong match
         * would suppress a genuine re-block.
         */
        fun pipPackage(windows: List<PipWindow>): String? = windows
            .firstOrNull { it.isPictureInPicture && !it.packageName.isNullOrBlank() }
            ?.packageName
    }
}
