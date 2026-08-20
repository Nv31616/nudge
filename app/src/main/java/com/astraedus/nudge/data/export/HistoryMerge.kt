package com.astraedus.nudge.data.export

import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.UsageEventKey

/**
 * The merge policy for imported usage history. Pure -- no DB, no Android -- so the whole contract
 * ("re-importing the same file changes nothing") is decided in JVM tests rather than on a device.
 *
 * History MERGES, it never replaces. Restoring a backup onto a phone that already has history must
 * add what is missing and touch nothing else: a user restoring twice, or restoring an old backup
 * over newer usage, must not lose rows and must not double their counts.
 */
object HistoryMerge {

    /** Rows are inserted in batches of this size so one import never builds a single huge statement. */
    const val INSERT_BATCH_SIZE = 500

    fun keyOf(event: ExportedHistoryEvent): UsageEventKey = UsageEventKey(
        packageName = event.packageName,
        timestamp = event.timestamp,
        wasBlocked = event.wasBlocked,
        userChangedMind = event.userChangedMind
    )

    fun keyOf(event: UsageEvent): UsageEventKey = UsageEventKey(
        packageName = event.packageName,
        timestamp = event.timestamp,
        wasBlocked = event.wasBlocked,
        userChangedMind = event.userChangedMind
    )

    /**
     * Inclusive timestamp span of [events], or null when there is nothing to import.
     *
     * The dedup pass only needs to know about rows that could possibly collide, so this bounds the
     * existing-key query instead of loading the whole table: a heavy user's history is unbounded
     * (`UsageRepository.cleanup` still has no call site), and an import of last week's backup must
     * not pull three years of rows into memory to answer a question about last week.
     */
    fun timestampRange(events: List<ExportedHistoryEvent>): LongRange? {
        if (events.isEmpty()) return null
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        for (event in events) {
            if (event.timestamp < min) min = event.timestamp
            if (event.timestamp > max) max = event.timestamp
        }
        return min..max
    }

    /**
     * The subset of [incoming] not already present in [existingKeys].
     *
     * Duplicates WITHIN the file are deliberately kept: two identical rows in one export means the
     * source device really had two, and dropping one would quietly shrink a restored history.
     * Idempotency does not depend on it -- on a second import both of those rows are already in the
     * DB, so both are recognised and skipped.
     */
    fun selectNew(
        incoming: List<ExportedHistoryEvent>,
        existingKeys: Set<UsageEventKey>
    ): List<ExportedHistoryEvent> =
        if (existingKeys.isEmpty()) incoming else incoming.filterNot { keyOf(it) in existingKeys }

    /**
     * Maps an imported event to a row. `id = 0` so Room autogenerates a fresh local id -- the
     * exporting device's ids are its own and would collide on any phone that already has history.
     */
    fun toEntity(event: ExportedHistoryEvent): UsageEvent = UsageEvent(
        packageName = event.packageName,
        timestamp = event.timestamp,
        wasBlocked = event.wasBlocked,
        blockMode = event.blockMode,
        userChangedMind = event.userChangedMind
    )

    fun toExported(event: UsageEvent): ExportedHistoryEvent = ExportedHistoryEvent(
        packageName = event.packageName,
        timestamp = event.timestamp,
        wasBlocked = event.wasBlocked,
        blockMode = event.blockMode,
        userChangedMind = event.userChangedMind
    )
}
