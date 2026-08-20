package com.astraedus.nudge.ui.screens.rules

import com.astraedus.nudge.data.export.ExportedGroup
import com.astraedus.nudge.data.export.ExportedRule
import com.astraedus.nudge.data.export.ImportResult
import com.astraedus.nudge.domain.usecase.ImportOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-facing half of [issue #20](https://github.com/astraedus/nudge/issues/20). Skipping a bad
 * rule instead of discarding the backup is only safe if the user is TOLD -- a silent skip is quiet
 * data loss with extra steps. These pin the wording so a refactor cannot drop the disclosure.
 */
class ImportMessagesTest {

    private fun rule(pkg: String) = ExportedRule(
        packageName = pkg,
        groupName = null,
        mode = "DELAY",
        delaySeconds = 15,
        dailyLimitMinutes = null,
        enabled = true,
        scheduleDays = null,
        scheduleStartMinute = null,
        scheduleEndMinute = null,
        inAppFeatures = null,
        grayscale = false,
        showCounter = false,
        autoKickAfter = null,
        showTimeRemaining = false,
        autoKickCooldownSeconds = 60
    )

    // --- Preview ---------------------------------------------------------------------------

    @Test
    fun `preview warns about entries that will be left out before anything is written`() {
        val message = buildImportPreviewMessage(
            ImportResult(
                rules = listOf(rule("com.app1")),
                groups = emptyList(),
                version = 1,
                invalidCount = 2,
                invalidReasons = listOf("Rule 2: Unknown block mode: TELEPORT", "Rule 5: No value for mode")
            )
        )

        assertTrue(message.contains("Import 1 rule(s)"))
        assertTrue(message.contains("2 entries could not be read"))
        assertTrue(message.contains("TELEPORT"))
    }

    @Test
    fun `preview says nothing about invalid entries when there are none`() {
        val message = buildImportPreviewMessage(
            ImportResult(rules = listOf(rule("com.app1")), groups = emptyList(), version = 1)
        )

        assertFalse(message.contains("could not be read"))
        assertTrue(message.contains("Duplicate rules will be skipped."))
    }

    /**
     * Regression: the group count used to be concatenated with a dangling `else` branch, so a file
     * WITH groups silently lost the "?" and the duplicate warning off the end of the sentence.
     */
    @Test
    fun `preview keeps the full sentence when the file contains groups`() {
        val message = buildImportPreviewMessage(
            ImportResult(
                rules = listOf(rule("com.app1")),
                groups = listOf(ExportedGroup("Social", listOf("com.instagram.android"))),
                version = 1
            )
        )

        assertTrue(message.contains("Import 1 rule(s) and 1 group(s)?"))
        assertTrue(message.contains("Duplicate rules will be skipped."))
    }

    // --- Outcome ---------------------------------------------------------------------------

    @Test
    fun `outcome reports unreadable entries separately from duplicates`() {
        val message = buildImportOutcomeMessage(
            ImportOutcome(
                importedCount = 4,
                duplicateCount = 2,
                groupsCreated = 1,
                invalidCount = 3,
                invalidReasons = listOf("Rule 2: bad", "Rule 7: bad", "Group 1: bad")
            )
        )

        assertTrue(message.contains("Imported: 4 rule(s)"))
        assertTrue(message.contains("Groups created: 1"))
        assertTrue(message.contains("Skipped (duplicates): 2"))
        assertTrue(message.contains("Skipped (could not be read): 3"))
    }

    @Test
    fun `outcome stays quiet about counts that are zero`() {
        val message = buildImportOutcomeMessage(
            ImportOutcome(importedCount = 3, duplicateCount = 0, groupsCreated = 0)
        )

        assertTrue(message.contains("Imported: 3 rule(s)"))
        assertFalse(message.contains("duplicates"))
        assertFalse(message.contains("could not be read"))
        assertFalse(message.contains("Groups created"))
    }

    @Test
    fun `a long list of reasons is collapsed instead of filling the dialog`() {
        val message = buildImportOutcomeMessage(
            ImportOutcome(
                importedCount = 1,
                duplicateCount = 0,
                groupsCreated = 0,
                invalidCount = 9,
                invalidReasons = (1..9).map { "Rule $it: bad" }
            )
        )

        assertTrue(message.contains("Skipped (could not be read): 9"))
        assertTrue(message.contains("...and 6 more"))
        assertFalse("only the first few reasons are listed", message.contains("Rule 9: bad"))
    }
}
