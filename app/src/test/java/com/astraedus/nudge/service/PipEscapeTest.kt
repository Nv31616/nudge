package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for issue #19: picture-in-picture defeats feature blocking.
 *
 * The bug: when the block overlay backgrounds YouTube, YouTube enters PiP and the Short keeps
 * playing in a floating always-on-top window. The overlay is correctly fullscreen and the top
 * resumed activity and still loses — platform behaviour, not an overlay bug.
 *
 * Nudge cannot flip the per-app PiP permission on the user's behalf, so the fix is
 * detect-and-deep-link. Two things have to be right, and both are covered here:
 *
 *  1. **Detection** — recognise the escape without paying for a window-list binder read on every
 *     event ([NudgeAccessibilityService.isPipEscapeOfActiveBlock] + [PipWindowProbe]).
 *  2. **Honest stats** — a PiP window fires window events carrying the blocked app's package, which
 *     [NudgeAccessibilityService.isOverlayBypassedByForeground] reads as "the user came back". Left
 *     alone that clears the overlay flag, re-evaluates and logs a fresh `wasBlocked` usage event on
 *     every PiP event, so the blocked count would climb on its own while a Short auto-played.
 */
class PipEscapeTest {

    private val youtube = "com.google.android.youtube"
    private val instagram = "com.instagram.android"
    private val ownPackage = "com.astraedus.nudge"

    private fun neverRead(): String? =
        throw AssertionError("the PiP window list must not be read for a cheaply-rejected event")

    // --- isPipEscapeOfActiveBlock: the decision ---

    @Test
    fun `the blocked app slipping into picture-in-picture is recognised as an escape`() {
        // The issue #19 scenario: we are blocking YouTube, YouTube now owns a PiP window.
        assertTrue(
            NudgeAccessibilityService.isPipEscapeOfActiveBlock(
                eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                packageName = youtube,
                blockedPackage = youtube,
                pipPackage = { youtube }
            )
        )
    }

    @Test
    fun `a window state change from the blocked app in PiP is an escape, not a foreground return`() {
        // This is the stats-honesty case. isOverlayBypassedByForeground says "bypass" for exactly
        // this input; the PiP check runs first and must claim it, otherwise every PiP event
        // re-blocks and logs another wasBlocked event for an app the user never re-opened.
        assertTrue(
            NudgeAccessibilityService.isPipEscapeOfActiveBlock(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = youtube,
                blockedPackage = youtube,
                pipPackage = { youtube }
            )
        )
        // Sanity: the bypass check really would have claimed it, so the ordering above is load-bearing.
        assertTrue(
            NudgeAccessibilityService.isOverlayBypassedByForeground(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = youtube,
                ownPackageName = ownPackage,
                currentImePackage = null
            )
        )
    }

    @Test
    fun `the blocked app genuinely returning to the foreground is NOT an escape`() {
        // Nothing in PiP: the user really did tab back in, so the normal re-block path must run.
        assertFalse(
            NudgeAccessibilityService.isPipEscapeOfActiveBlock(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = youtube,
                blockedPackage = youtube,
                pipPackage = { null }
            )
        )
    }

    @Test
    fun `a different app in PiP while we block this one is not this block's escape`() {
        // A podcast floating in PiP while Instagram is blocked is not Instagram escaping. Suppressing
        // the bypass here would wrongly hold a block the user actually walked back into.
        assertFalse(
            NudgeAccessibilityService.isPipEscapeOfActiveBlock(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = instagram,
                blockedPackage = instagram,
                pipPackage = { youtube }
            )
        )
    }

    @Test
    fun `a different app coming to the foreground is rejected without reading the window list`() {
        // Cost + correctness in one: the event package must match the blocked package before the
        // binder read, and a genuine switch to another app must still reach the bypass path.
        assertFalse(
            NudgeAccessibilityService.isPipEscapeOfActiveBlock(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = instagram,
                blockedPackage = youtube,
                pipPackage = ::neverRead
            )
        )
    }

    @Test
    fun `our own overlay's window events are rejected without reading the window list`() {
        // The overwhelmingly common event on this branch: the block overlay's own window churning
        // while it is on screen. It must cost nothing.
        assertFalse(
            NudgeAccessibilityService.isPipEscapeOfActiveBlock(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = ownPackage,
                blockedPackage = youtube,
                pipPackage = ::neverRead
            )
        )
    }

    @Test
    fun `content-change churn never reads the window list`() {
        // Content changes arrive in bursts (a device capture measured ~26k in a few minutes of
        // Instagram use). Only true window changes may reach the binder read.
        assertFalse(
            NudgeAccessibilityService.isPipEscapeOfActiveBlock(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = youtube,
                blockedPackage = youtube,
                pipPackage = ::neverRead
            )
        )
    }

    @Test
    fun `with no live block there is nothing to escape from, and nothing is read`() {
        assertFalse(
            NudgeAccessibilityService.isPipEscapeOfActiveBlock(
                eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                packageName = youtube,
                blockedPackage = null,
                pipPackage = ::neverRead
            )
        )
    }

    // --- PipWindowProbe.pipPackage: picking the PiP owner out of the window list ---

    @Test
    fun `the PiP window's owner is picked out of a list of ordinary windows`() {
        val owner = PipWindowProbe.pipPackage(
            listOf(
                PipWindow(packageName = null, isPictureInPicture = false),
                PipWindow(packageName = null, isPictureInPicture = false),
                PipWindow(packageName = youtube, isPictureInPicture = true)
            )
        )
        assertEquals(youtube, owner)
    }

    @Test
    fun `no PiP window means no owner`() {
        assertNull(
            PipWindowProbe.pipPackage(
                listOf(
                    PipWindow(packageName = null, isPictureInPicture = false),
                    PipWindow(packageName = youtube, isPictureInPicture = false)
                )
            )
        )
        assertNull(PipWindowProbe.pipPackage(emptyList()))
    }

    @Test
    fun `a PiP window whose owner could not be resolved is skipped, never guessed at`() {
        // getRoot() can fail or return null. The result is compared against the package we are
        // blocking, so a guess here would suppress a genuine re-block.
        assertNull(
            PipWindowProbe.pipPackage(
                listOf(
                    PipWindow(packageName = null, isPictureInPicture = true),
                    PipWindow(packageName = "  ", isPictureInPicture = true)
                )
            )
        )
        // ...but a resolvable one later in the list is still found.
        assertEquals(
            youtube,
            PipWindowProbe.pipPackage(
                listOf(
                    PipWindow(packageName = null, isPictureInPicture = true),
                    PipWindow(packageName = youtube, isPictureInPicture = true)
                )
            )
        )
    }

    // --- PipWindowProbe: the throttle that keeps the binder read off the hot path ---

    @Test
    fun `repeat questions inside the throttle window reuse one window read`() {
        var reads = 0
        var now = 1_000L
        val probe = PipWindowProbe(
            throttleMs = 500L,
            clock = { now },
            readWindows = {
                reads++
                listOf(PipWindow(youtube, isPictureInPicture = true))
            }
        )

        assertEquals(youtube, probe.packageInPictureInPicture())
        now = 1_100L
        assertEquals(youtube, probe.packageInPictureInPicture())
        now = 1_499L
        assertEquals(youtube, probe.packageInPictureInPicture())

        assertEquals("a burst of window events must cost one binder read, not three", 1, reads)
    }

    @Test
    fun `the window list is re-read once the throttle window has elapsed`() {
        var reads = 0
        var now = 1_000L
        var inPip = true
        val probe = PipWindowProbe(
            throttleMs = 500L,
            clock = { now },
            readWindows = {
                reads++
                if (inPip) listOf(PipWindow(youtube, isPictureInPicture = true)) else emptyList()
            }
        )

        assertEquals(youtube, probe.packageInPictureInPicture())
        inPip = false
        now = 1_500L
        assertNull("PiP closing must be observed on the next read", probe.packageInPictureInPicture())
        assertEquals(2, reads)
    }

    @Test
    fun `the very first question always reads (no false cache hit at time zero)`() {
        // Regression guard for the sentinel: a naive `now - lastReadAt < throttle` with a zero-
        // initialised timestamp reports a cache hit at clock 0 and the probe answers null forever.
        var reads = 0
        val probe = PipWindowProbe(
            throttleMs = 500L,
            clock = { 0L },
            readWindows = {
                reads++
                listOf(PipWindow(youtube, isPictureInPicture = true))
            }
        )
        assertEquals(youtube, probe.packageInPictureInPicture())
        assertEquals(1, reads)
    }
}
