package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.export.HistoryMerge
import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end cover for [issue #20](https://github.com/astraedus/nudge/issues/20): the counts a user
 * is shown after an import ("imported N, skipped M") must be true all the way from the raw JSON to
 * [ImportOutcome], and a rule the parser could not read must not be able to stop the others from
 * reaching the database.
 *
 * Uses the real [RuleExporter] on purpose -- the bug lived in the seam between parsing and
 * inserting, so a fake parser here would test nothing.
 */
class ImportRulesUseCaseTest {

    private lateinit var repository: BlockRuleRepository
    private lateinit var usageRepository: UsageRepository
    private lateinit var useCase: ImportRulesUseCase
    private lateinit var addedRules: MutableList<BlockRule>
    private lateinit var createdGroups: MutableList<AppGroup>
    private lateinit var addedMembers: MutableList<AppGroupMember>

    /** Stands in for `usage_events`: what the fake DB holds, and what a second import sees. */
    private lateinit var storedEvents: MutableList<UsageEvent>

    @Before
    fun setUp() {
        repository = mockk()
        usageRepository = mockk()
        addedRules = mutableListOf()
        createdGroups = mutableListOf()
        addedMembers = mutableListOf()
        storedEvents = mutableListOf()

        // A real (if tiny) table rather than a stub: idempotency is a property of the SECOND
        // import, so the fake has to actually remember what the first one wrote.
        coEvery { usageRepository.getEventKeysInRange(any(), any()) } answers {
            val from = firstArg<Long>()
            val to = secondArg<Long>()
            storedEvents.filter { it.timestamp in from..to }.map(HistoryMerge::keyOf)
        }
        coEvery { usageRepository.insertEvents(any()) } answers {
            storedEvents.addAll(firstArg<List<UsageEvent>>())
        }

        every { repository.getAllRules() } returns flowOf(emptyList())
        every { repository.getAllGroups() } returns flowOf(emptyList())
        every { repository.getGroupMembers(any()) } returns flowOf(emptyList())
        coEvery { repository.addRule(any()) } answers {
            addedRules.add(firstArg())
            addedRules.size.toLong()
        }
        coEvery { repository.createGroup(any()) } answers {
            createdGroups.add(firstArg())
            createdGroups.size.toLong()
        }
        coEvery { repository.addToGroup(any()) } answers { addedMembers.add(firstArg()); Unit }

        useCase = ImportRulesUseCase(repository, usageRepository, RuleExporter())
    }

    private suspend fun import(json: String): ImportOutcome =
        useCase.execute(useCase.preview(json).result)

    @Test
    fun `a mixed file imports its valid rules and reports the ones it could not read`() = runTest {
        val outcome = import(
            """
            {
                "version": 1,
                "rules": [
                    {"packageName": "com.app1", "mode": "DELAY", "delaySeconds": 15},
                    {"packageName": "com.app2", "mode": "TELEPORT"},
                    {"packageName": "com.app3", "mode": "HARD_BLOCK"}
                ]
            }
            """.trimIndent()
        )

        assertNull(outcome.error)
        assertEquals(2, outcome.importedCount)
        assertEquals(1, outcome.invalidCount)
        assertEquals(0, outcome.duplicateCount)
        assertEquals(listOf("com.app1", "com.app3"), addedRules.map { it.packageName })
        assertTrue(outcome.invalidReasons.single().contains("TELEPORT"))
    }

    @Test
    fun `groups and their members still import when a rule is invalid`() = runTest {
        val outcome = import(
            """
            {
                "version": 1,
                "rules": [
                    {"packageName": "com.app1", "mode": "NONSENSE"},
                    {"groupName": "Social", "mode": "HARD_BLOCK"}
                ],
                "groups": [
                    {"name": "Social", "members": ["com.instagram.android", "com.twitter.android"]}
                ]
            }
            """.trimIndent()
        )

        assertNull(outcome.error)
        assertEquals(1, outcome.groupsCreated)
        assertEquals(1, outcome.importedCount)
        assertEquals(1, outcome.invalidCount)
        assertEquals(listOf("Social"), createdGroups.map { it.name })
        assertEquals(
            listOf("com.instagram.android", "com.twitter.android"),
            addedMembers.map { it.packageName }
        )
        // The surviving group rule must be wired to the group that was just created.
        assertEquals(1L, addedRules.single().groupId)
    }

    @Test
    fun `duplicates and unreadable entries are counted separately`() = runTest {
        every { repository.getAllRules() } returns flowOf(
            listOf(BlockRule(id = 1, packageName = "com.app1", mode = "DELAY", delaySeconds = 15))
        )

        val outcome = import(
            """
            {
                "version": 1,
                "rules": [
                    {"packageName": "com.app1", "mode": "DELAY", "delaySeconds": 15},
                    {"packageName": "com.app2", "mode": "TELEPORT"},
                    {"packageName": "com.app3", "mode": "BREATHING"}
                ]
            }
            """.trimIndent()
        )

        assertEquals(1, outcome.importedCount)
        assertEquals(1, outcome.duplicateCount)
        assertEquals(1, outcome.invalidCount)
        assertEquals(listOf("com.app3"), addedRules.map { it.packageName })
    }

    @Test
    fun `a file with nothing readable writes nothing and surfaces the error`() = runTest {
        val outcome = import(
            """{"version": 1, "rules": [{"packageName": "com.app1", "mode": "TELEPORT"}]}"""
        )

        assertNotNull(outcome.error)
        assertEquals(0, outcome.importedCount)
        assertEquals(1, outcome.invalidCount)
        assertTrue("nothing may reach the database", addedRules.isEmpty())
    }

    @Test
    fun `a file that is not a Nudge export writes nothing`() = runTest {
        val outcome = import("""{"totally": "unrelated"}""")

        assertNotNull(outcome.error)
        assertEquals(0, outcome.importedCount)
        assertTrue(addedRules.isEmpty())
        assertTrue(createdGroups.isEmpty())
    }

    @Test
    fun `a clean file reports zero skipped`() = runTest {
        val outcome = import(
            """
            {
                "version": 1,
                "rules": [
                    {"packageName": "com.app1", "mode": "DELAY"},
                    {"packageName": "com.app2", "mode": "HARD_BLOCK"}
                ]
            }
            """.trimIndent()
        )

        assertNull(outcome.error)
        assertEquals(2, outcome.importedCount)
        assertEquals(0, outcome.invalidCount)
        assertEquals(0, outcome.duplicateCount)
        assertTrue(outcome.invalidReasons.isEmpty())
    }

    // --- History ---------------------------------------------------------------------------

    private fun historyJson(vararg events: String) = """
        {
            "version": 1,
            "rules": [{"packageName": "com.app1", "mode": "DELAY"}],
            "history": [${events.joinToString(",")}]
        }
    """.trimIndent()

    private fun event(
        pkg: String = "com.app1",
        ts: Long,
        blocked: Boolean = true,
        mode: String? = "DELAY",
        changedMind: Boolean = false
    ) = """{"packageName":"$pkg","timestamp":$ts,"wasBlocked":$blocked,""" +
        """"blockMode":${mode?.let { "\"$it\"" } ?: "null"},"userChangedMind":$changedMind}"""

    @Test
    fun `history events reach the database and are counted`() = runTest {
        val outcome = import(
            historyJson(
                event(ts = 1000),
                event(ts = 2000, changedMind = true),
                event(pkg = "com.app2", ts = 3000, blocked = false, mode = null)
            )
        )

        assertNull(outcome.error)
        assertEquals(3, outcome.historyImportedCount)
        assertEquals(0, outcome.historyDuplicateCount)
        assertEquals(3, storedEvents.size)
        assertEquals(listOf(1000L, 2000L, 3000L), storedEvents.map { it.timestamp })
        assertEquals(listOf("DELAY", "DELAY", null), storedEvents.map { it.blockMode })
        assertEquals(listOf(true, true, false), storedEvents.map { it.wasBlocked })
        assertEquals(listOf(false, true, false), storedEvents.map { it.userChangedMind })
        // Row ids belong to the importing device, never to the file.
        assertTrue("imported rows must take fresh local ids", storedEvents.all { it.id == 0L })
    }

    /**
     * The headline property: restoring the same backup twice must be a no-op for history. Anything
     * else silently doubles every number on the dashboard and both insight pages.
     */
    @Test
    fun `re-importing the same file adds no history a second time`() = runTest {
        val json = historyJson(event(ts = 1000), event(ts = 2000, changedMind = true))

        val first = import(json)
        val second = import(json)

        assertEquals(2, first.historyImportedCount)
        assertEquals(0, second.historyImportedCount)
        assertEquals(2, second.historyDuplicateCount)
        assertEquals("the table must be unchanged by the second import", 2, storedEvents.size)
    }

    @Test
    fun `a partially overlapping file adds only the events that are new`() = runTest {
        import(historyJson(event(ts = 1000), event(ts = 2000)))

        val outcome = import(historyJson(event(ts = 2000), event(ts = 3000), event(ts = 4000)))

        assertEquals(2, outcome.historyImportedCount)
        assertEquals(1, outcome.historyDuplicateCount)
        assertEquals(listOf(1000L, 2000L, 3000L, 4000L), storedEvents.map { it.timestamp }.sorted())
    }

    /**
     * The dedup key is (package, timestamp, wasBlocked, userChangedMind). A walk-away writes a
     * SECOND row for the same confrontation, so two rows can legitimately share a package and be
     * milliseconds apart -- and the show row and the walk-away row of the same moment must both
     * survive, or the "Blocked" and "Walked Away" tiles stop agreeing with each other.
     */
    @Test
    fun `events that differ only in their flags are not treated as duplicates`() = runTest {
        val outcome = import(
            historyJson(
                event(ts = 1000, changedMind = false),
                event(ts = 1000, changedMind = true),
                event(pkg = "com.other", ts = 1000)
            )
        )

        assertEquals(3, outcome.historyImportedCount)
        assertEquals(3, storedEvents.size)
    }

    @Test
    fun `unreadable history is skipped and reported without touching the rules`() = runTest {
        val outcome = import(
            """
            {
                "version": 1,
                "rules": [{"packageName": "com.app1", "mode": "DELAY"}],
                "history": [
                    {"packageName": "com.app1", "timestamp": 1000, "wasBlocked": true},
                    {"timestamp": 2000, "wasBlocked": true},
                    {"packageName": "com.app1", "timestamp": "yesterday"},
                    "not an event"
                ]
            }
            """.trimIndent()
        )

        assertNull("bad history must never fail the file", outcome.error)
        assertEquals(1, outcome.importedCount)
        assertEquals("rule skips stay their own number", 0, outcome.invalidCount)
        assertEquals(3, outcome.historyInvalidCount)
        assertEquals(1, outcome.historyImportedCount)
        assertEquals(listOf("com.app1"), addedRules.map { it.packageName })
    }

    /** A pre-history backup must import exactly as it always did, and say nothing about history. */
    @Test
    fun `a rules-only file imports with no history activity at all`() = runTest {
        val outcome = import("""{"version": 1, "rules": [{"packageName": "com.app1", "mode": "DELAY"}]}""")

        assertNull(outcome.error)
        assertEquals(1, outcome.importedCount)
        assertEquals(0, outcome.historyImportedCount)
        assertEquals(0, outcome.historyDuplicateCount)
        assertEquals(0, outcome.historyInvalidCount)
        assertTrue(storedEvents.isEmpty())
    }

    /**
     * A file whose rules are all unreadable but whose history is fine is NOT a total failure --
     * something was restorable, so it must not be rejected wholesale (and the history must land).
     */
    @Test
    fun `history survives a file whose every rule is unreadable`() = runTest {
        val outcome = import(
            """
            {
                "version": 1,
                "rules": [{"packageName": "com.app1", "mode": "TELEPORT"}],
                "history": [${event(ts = 1000)}]
            }
            """.trimIndent()
        )

        assertNull(outcome.error)
        assertEquals(0, outcome.importedCount)
        assertEquals(1, outcome.invalidCount)
        assertEquals(1, outcome.historyImportedCount)
        assertEquals(1, storedEvents.size)
    }

    @Test
    fun `the preview reports how much history is new, not how much the file holds`() = runTest {
        val json = historyJson(event(ts = 1000), event(ts = 2000))
        useCase.execute(useCase.preview(json).result)

        val second = useCase.preview(historyJson(event(ts = 2000), event(ts = 3000)))

        assertEquals(2, second.result.history.size)
        assertEquals(1, second.newHistoryCount)
        assertEquals("a preview must not write anything", 2, storedEvents.size)
    }

    /**
     * The dedup read is bounded to the file's own timestamp window -- restoring one week of history
     * must not drag years of rows off disk to answer a question about that week.
     */
    @Test
    fun `the duplicate check only reads the window the file covers`() = runTest {
        import(historyJson(event(ts = 5_000), event(ts = 9_000)))

        coVerify { usageRepository.getEventKeysInRange(5_000L, 9_000L) }
    }
}
