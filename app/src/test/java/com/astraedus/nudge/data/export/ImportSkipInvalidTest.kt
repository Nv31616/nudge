package com.astraedus.nudge.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression suite for [issue #20](https://github.com/astraedus/nudge/issues/20):
 * "Import should skip invalid rules instead of discarding the whole backup."
 *
 * `parseRules` used to map eagerly over the rules array, so the FIRST unparseable entry threw out
 * of the loop and the catch returned an empty list -- one bad rule silently cost the user every
 * rule and every group in the file, on the app's only backup path. The immediate trigger
 * (BlockMode.NONE missing from a hand-written whitelist) was fixed separately; these tests pin the
 * failure SHAPE, which is the part that generalizes to the next unexpected field.
 *
 * The contract: entry-level damage is isolated and counted, envelope-level damage still fails loud.
 */
class ImportSkipInvalidTest {

    private lateinit var exporter: RuleExporter

    @Before
    fun setUp() {
        exporter = RuleExporter()
    }

    // --- The headline case ---------------------------------------------------------------

    @Test
    fun `one invalid rule does not discard the rest of the backup`() {
        val json = """
        {
            "version": 1,
            "exportedAt": 1000,
            "rules": [
                {"packageName": "com.app1", "mode": "DELAY", "delaySeconds": 15},
                {"packageName": "com.app2", "mode": "TELEPORT"},
                {"packageName": "com.app3", "mode": "HARD_BLOCK"}
            ],
            "groups": [
                {"name": "Social", "members": ["com.instagram.android"]}
            ]
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull("a skippable rule must not fail the file", result.error)
        assertEquals(listOf("com.app1", "com.app3"), result.rules.map { it.packageName })
        assertEquals(1, result.invalidCount)
        assertEquals(1, result.groups.size)
        assertEquals("Social", result.groups[0].name)
        assertEquals(listOf("com.instagram.android"), result.groups[0].members)
    }

    @Test
    fun `the skip reason names the offending entry and what was wrong with it`() {
        val json = """
        {
            "version": 1,
            "rules": [
                {"packageName": "com.app1", "mode": "DELAY"},
                {"packageName": "com.app2", "mode": "TELEPORT"}
            ]
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertEquals(1, result.invalidReasons.size)
        val reason = result.invalidReasons.single()
        assertTrue("reason should point at rule 2, was: $reason", reason.startsWith("Rule 2:"))
        assertTrue("reason should name the bad mode, was: $reason", reason.contains("TELEPORT"))
    }

    // --- Every flavour of a broken entry --------------------------------------------------

    @Test
    fun `a rule missing its mode is skipped, not fatal`() {
        val json = """
        {
            "version": 1,
            "rules": [
                {"packageName": "com.app1"},
                {"packageName": "com.app2", "mode": "BREATHING"}
            ]
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(listOf("com.app2"), result.rules.map { it.packageName })
        assertEquals(1, result.invalidCount)
    }

    @Test
    fun `an array element that is not an object at all is skipped`() {
        val json = """
        {
            "version": 1,
            "rules": [
                "this is not a rule",
                {"packageName": "com.app2", "mode": "DELAY"},
                12345
            ]
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(listOf("com.app2"), result.rules.map { it.packageName })
        assertEquals(2, result.invalidCount)
    }

    @Test
    fun `a field of the wrong type is skipped, not fatal`() {
        val json = """
        {
            "version": 1,
            "rules": [
                {"packageName": "com.app1", "mode": "DELAY", "dailyLimitMinutes": "sixty"},
                {"packageName": "com.app2", "mode": "DELAY"}
            ]
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(listOf("com.app2"), result.rules.map { it.packageName })
        assertEquals(1, result.invalidCount)
    }

    /**
     * Forward compatibility: a backup written by a NEWER Nudge carries fields this build has never
     * heard of. Refusing those rules would make the file unrestorable for no reason -- unknown keys
     * are ignored, and only fields we actually validate can disqualify an entry.
     */
    @Test
    fun `unknown schema junk on an otherwise valid rule is ignored`() {
        val json = """
        {
            "version": 1,
            "rules": [
                {
                    "packageName": "com.app1",
                    "mode": "DELAY",
                    "delaySeconds": 30,
                    "quantumEntanglement": true,
                    "futureField": {"nested": [1, 2, 3]}
                }
            ]
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertEquals(0, result.invalidCount)
        assertEquals(30, result.rules[0].delaySeconds)
    }

    // --- Groups get the same isolation ----------------------------------------------------

    @Test
    fun `an invalid group is skipped while rules and other groups still import`() {
        val json = """
        {
            "version": 1,
            "rules": [{"packageName": "com.app1", "mode": "DELAY"}],
            "groups": [
                {"members": ["com.a"]},
                {"name": "Social", "members": ["com.instagram.android"]},
                {"name": "   "}
            ]
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertEquals(listOf("Social"), result.groups.map { it.name })
        assertEquals(2, result.invalidCount)
        assertTrue(result.invalidReasons.any { it.startsWith("Group 1:") })
        assertTrue(result.invalidReasons.any { it.startsWith("Group 3:") })
    }

    @Test
    fun `a broken group does not take the rules down with it`() {
        val json = """
        {
            "version": 1,
            "rules": [
                {"packageName": "com.app1", "mode": "DELAY"},
                {"packageName": "com.app2", "mode": "HARD_BLOCK"}
            ],
            "groups": ["not a group"]
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(2, result.rules.size)
        assertEquals(1, result.invalidCount)
    }

    // --- A clean file reports nothing skipped ---------------------------------------------

    @Test
    fun `a fully valid file reports no skipped entries`() {
        val json = """
        {
            "version": 1,
            "rules": [
                {"packageName": "com.app1", "mode": "DELAY"},
                {"packageName": "com.app2", "mode": "HARD_BLOCK"}
            ],
            "groups": [{"name": "Social", "members": []}]
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(0, result.invalidCount)
        assertTrue(result.invalidReasons.isEmpty())
    }

    // --- Loud failures survive: nothing importable is NOT a success -----------------------

    @Test
    fun `a file whose every entry is invalid fails loudly instead of importing nothing`() {
        val json = """
        {
            "version": 1,
            "rules": [
                {"packageName": "com.app1", "mode": "TELEPORT"},
                {"packageName": "com.app2", "mode": "WARP"}
            ]
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNotNull("skipping everything must not read as a successful import", result.error)
        assertEquals(2, result.invalidCount)
        assertTrue(result.error!!.contains("TELEPORT"))
        assertTrue(result.error!!.contains("WARP"))
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `the all-invalid message caps how many reasons it quotes`() {
        val rules = (1..10).joinToString(",") { """{"packageName": "com.app$it", "mode": "NOPE$it"}""" }
        val result = exporter.importRules("""{"version": 1, "rules": [$rules]}""")

        assertNotNull(result.error)
        assertEquals(10, result.invalidCount)
        assertEquals(10, result.invalidReasons.size)
        assertTrue("should collapse the tail, was: ${result.error}", result.error!!.contains("7 more"))
    }

    /**
     * The reason strings are display material and are driven by file content -- picking the wrong
     * (large) file must not allocate one string per entry. The COUNT stays exact.
     */
    @Test
    fun `the retained reasons are bounded while the count stays exact`() {
        val bad = (1..200).joinToString(",") { """{"packageName": "com.app$it", "mode": "NOPE"}""" }
        val json = """{"version": 1, "rules": [{"packageName": "com.ok", "mode": "DELAY"}, $bad]}"""

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertEquals(200, result.invalidCount)
        assertTrue(
            "reasons should be capped, was ${result.invalidReasons.size}",
            result.invalidReasons.size in 1..20
        )
    }

    /**
     * An EMPTY backup is a legitimate thing to restore (the user had no rules). It must stay a
     * success -- only a file where entries existed and none could be read is an error.
     */
    @Test
    fun `an empty but well-formed file is not an error`() {
        val result = exporter.importRules("""{"version": 1, "rules": [], "groups": []}""")

        assertNull(result.error)
        assertEquals(0, result.rules.size)
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `a file that is not JSON at all still fails`() {
        val result = exporter.importRules("not json at all {{{")

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Invalid JSON"))
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `a rules key that is not an array fails loudly rather than importing zero rules`() {
        val result = exporter.importRules("""{"version": 1, "rules": {"packageName": "com.app1"}}""")

        assertNotNull("wrong envelope shape must not silently import nothing", result.error)
        assertTrue(result.error!!.contains("rules"))
    }

    @Test
    fun `a groups key that is not an array fails loudly`() {
        val json = """{"version": 1, "rules": [{"packageName": "com.a", "mode": "DELAY"}], "groups": 7}"""

        val result = exporter.importRules(json)

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("groups"))
    }

    @Test
    fun `an explicitly null groups key is treated as absent`() {
        val json = """{"version": 1, "rules": [{"packageName": "com.a", "mode": "DELAY"}], "groups": null}"""

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertEquals(0, result.groups.size)
    }
}
