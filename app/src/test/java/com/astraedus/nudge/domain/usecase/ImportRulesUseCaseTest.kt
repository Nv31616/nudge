package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.repository.BlockRuleRepository
import io.mockk.coEvery
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
    private lateinit var useCase: ImportRulesUseCase
    private lateinit var addedRules: MutableList<BlockRule>
    private lateinit var createdGroups: MutableList<AppGroup>
    private lateinit var addedMembers: MutableList<AppGroupMember>

    @Before
    fun setUp() {
        repository = mockk()
        addedRules = mutableListOf()
        createdGroups = mutableListOf()
        addedMembers = mutableListOf()

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

        useCase = ImportRulesUseCase(repository, RuleExporter())
    }

    private suspend fun import(json: String): ImportOutcome = useCase.execute(useCase.preview(json))

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
}
