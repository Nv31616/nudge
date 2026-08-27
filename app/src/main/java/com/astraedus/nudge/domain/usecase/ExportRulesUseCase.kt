package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ExportRulesUseCase @Inject constructor(
    private val repository: BlockRuleRepository,
    private val usageRepository: UsageRepository,
    private val preferences: NudgePreferences,
    private val exporter: RuleExporter
) {

    /**
     * Exports all enabled rules, their groups, the FULL block/walk-away history and the user's app
     * settings to JSON.
     *
     * History is always included and has no toggle: it is the thing that makes the dashboard tiles
     * and both insight pages survive a device move, and an opt-in nobody finds is a backup nobody
     * has. Screen time is not included — it belongs to `UsageStatsManager` and re-derives itself on
     * whatever device the file lands on.
     *
     * Serialization runs OFF the main thread. Retention is not enforced anywhere
     * (`UsageRepository.cleanup` has no call site), so the row count is unbounded and a heavy user's
     * history can be tens of thousands of events — building that string on the UI thread is an ANR.
     */
    suspend fun invoke(): String = withContext(Dispatchers.Default) {
        val rules = repository.getEnabledRules().firstOrNull() ?: emptyList()
        val groups = repository.getAllGroups().firstOrNull() ?: emptyList()

        // Collect group members for each group
        val groupMembers = groups.associate { group ->
            group.id to (repository.getGroupMembers(group.id).firstOrNull() ?: emptyList())
        }

        val history = usageRepository.getAllEventsForExport()

        // Settings ride along for the same reason history does: a backup that restores a user's
        // rules but not their custom block messages or their Strict Mode difficulty has not
        // actually restored their Nudge. Device-local state is excluded -- see [ExportedSettings].
        val settings = preferences.exportableSettings()

        exporter.exportRules(rules, groups, groupMembers, history, settings)
    }
}
