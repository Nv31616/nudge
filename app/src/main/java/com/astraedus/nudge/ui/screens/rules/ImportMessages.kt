package com.astraedus.nudge.ui.screens.rules

import com.astraedus.nudge.data.export.ImportResult
import com.astraedus.nudge.domain.usecase.ImportOutcome

/**
 * Pure text builders for the import dialogs.
 *
 * They live outside the composable so the wording -- especially "we skipped N entries you had in
 * your backup", the one thing the user must not miss -- is JVM-testable rather than only verifiable
 * by eye on a device.
 */

/** Cap on how many per-entry reasons a dialog lists before collapsing the rest into a count. */
private const val MAX_LISTED_REASONS = 3

/** Body text of the "Import Rules" confirmation dialog, shown BEFORE anything is written. */
fun buildImportPreviewMessage(preview: ImportResult): String = buildString {
    append("Import ${preview.rules.size} rule(s)")
    if (preview.groups.isNotEmpty()) append(" and ${preview.groups.size} group(s)")
    append("?\n\nDuplicate rules will be skipped.")
    if (preview.invalidCount > 0) {
        append("\n\n")
        append(entriesCouldNotBeRead(preview.invalidCount))
        append(" and will be left out:")
        appendReasons(preview.invalidReasons)
    }
}

/** Body text of the "Import Complete" dialog. */
fun buildImportOutcomeMessage(outcome: ImportOutcome): String = buildString {
    append("Imported: ${outcome.importedCount} rule(s)")
    if (outcome.groupsCreated > 0) append("\nGroups created: ${outcome.groupsCreated}")
    if (outcome.duplicateCount > 0) append("\nSkipped (duplicates): ${outcome.duplicateCount}")
    if (outcome.invalidCount > 0) {
        append("\n\nSkipped (could not be read): ${outcome.invalidCount}")
        appendReasons(outcome.invalidReasons)
    }
}

private fun StringBuilder.appendReasons(reasons: List<String>) {
    reasons.take(MAX_LISTED_REASONS).forEach { append("\n• $it") }
    val extra = reasons.size - MAX_LISTED_REASONS
    if (extra > 0) append("\n• ...and $extra more")
}

private fun entriesCouldNotBeRead(count: Int): String =
    if (count == 1) "1 entry could not be read" else "$count entries could not be read"
