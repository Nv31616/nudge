package com.astraedus.nudge.domain.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the prompt-once bookkeeping behind the issue #19 picture-in-picture explainer.
 *
 * The explainer teaches the user about a PLATFORM limitation Nudge cannot fix in code — the only
 * remedy is the per-app PiP permission in Settings. Showing it again after they have read it (and
 * possibly already acted on it) is pure nagging on an app blocker people already find abrasive, so
 * "once per package, ever" is the contract these tests pin.
 */
class PipEscapeLedgerTest {

    private val youtube = "com.google.android.youtube"
    private val chrome = "com.android.chrome"

    @Test
    fun `a fresh install has prompted for nothing`() {
        assertEquals(emptySet<String>(), PipEscapeLedger.parse(""))
        assertEquals(emptySet<String>(), PipEscapeLedger.parse("   "))
    }

    @Test
    fun `recording a package then reloading it reports that app as already prompted`() {
        // The whole feature in one assertion: escape, explain, persist, restart, do not explain again.
        val stored = PipEscapeLedger.serialize(PipEscapeLedger.record(emptySet(), youtube))
        val reloaded = PipEscapeLedger.parse(stored)

        assertTrue(youtube in reloaded)
        assertTrue("a different app has not been explained yet", chrome !in reloaded)
    }

    @Test
    fun `several apps round-trip independently`() {
        var prompted = PipEscapeLedger.record(emptySet(), youtube)
        prompted = PipEscapeLedger.record(prompted, chrome)

        val reloaded = PipEscapeLedger.parse(PipEscapeLedger.serialize(prompted))
        assertEquals(setOf(youtube, chrome), reloaded)
    }

    @Test
    fun `re-recording an already-prompted app is a no-op`() {
        // The service marks its in-memory cache before the DataStore write lands, so a duplicate
        // write is reachable from an event burst. It must not rotate a still-relevant entry out via
        // the size cap, nor reorder the ledger.
        val prompted = PipEscapeLedger.record(emptySet(), youtube)
        assertSame(prompted, PipEscapeLedger.record(prompted, youtube))
    }

    @Test
    fun `a blank package is never recorded`() {
        // event.packageName can be empty; an empty entry would serialize into a stray separator and
        // parse back as noise.
        assertEquals(emptySet<String>(), PipEscapeLedger.record(emptySet(), ""))
        assertEquals(emptySet<String>(), PipEscapeLedger.record(emptySet(), "   "))
    }

    @Test
    fun `garbage in prefs degrades to 'nothing prompted yet' rather than throwing`() {
        // This value is read on the accessibility hot path. Failing soft costs one extra explainer;
        // failing hard would take the service down, and failing soft the OTHER way (assuming
        // everything is prompted) would silently kill the feature.
        assertEquals(emptySet<String>(), PipEscapeLedger.parse(";;;"))
        assertEquals(setOf(youtube), PipEscapeLedger.parse(";;$youtube;; ;"))
    }

    @Test
    fun `the ledger is capped and drops the oldest entries first`() {
        var prompted = emptySet<String>()
        repeat(PipEscapeLedger.MAX_ENTRIES + 5) { i ->
            prompted = PipEscapeLedger.record(prompted, "com.example.app$i")
        }

        assertEquals(PipEscapeLedger.MAX_ENTRIES, prompted.size)
        assertTrue("the oldest entry is rotated out", "com.example.app0" !in prompted)
        assertTrue(
            "the most recently explained app is the one worth keeping",
            "com.example.app${PipEscapeLedger.MAX_ENTRIES + 4}" in prompted
        )
    }
}
