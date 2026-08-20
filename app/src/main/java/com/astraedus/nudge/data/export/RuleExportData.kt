package com.astraedus.nudge.data.export

/**
 * Data classes representing the JSON export format for Nudge rules.
 * Designed to be human-readable and portable between devices.
 */
data class NudgeExport(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val rules: List<ExportedRule>,
    val groups: List<ExportedGroup> = emptyList(),
    /**
     * Usage history -- one entry per `usage_events` row. OPTIONAL, and deliberately added at
     * envelope version 1 rather than as a version 2: every shipped version of the importer reads
     * the envelope by KNOWN KEY ONLY (`version`, `rules`, `groups`), so an older Nudge simply
     * ignores this array, whereas a version bump would make it reject the entire file as "newer
     * than supported" and cost the user their rules as well as their history.
     */
    val history: List<ExportedHistoryEvent> = emptyList()
)

data class ExportedRule(
    val packageName: String?,
    val groupName: String?, // Resolved from groupId -> group name for portability
    val mode: String,
    val delaySeconds: Int,
    val dailyLimitMinutes: Int?,
    val enabled: Boolean,
    val scheduleDays: String?,
    val scheduleStartMinute: Int?,
    val scheduleEndMinute: Int?,
    val inAppFeatures: String?,
    val grayscale: Boolean,
    val showCounter: Boolean,
    val autoKickAfter: Int?,
    val showTimeRemaining: Boolean,
    val autoKickCooldownSeconds: Int,
    val webDomains: String? = null,
    val autoKickAfterMinutes: Int? = null,
    /** Independent block mode for [webDomains]; null = inherit [mode]. See `BlockRule.webBlockMode`. */
    val webBlockMode: String? = null
)

data class ExportedGroup(
    val name: String,
    val members: List<String> // package names
)

/**
 * One row of block/walk-away history -- the exact shape of a `UsageEvent`, minus its local row id.
 *
 * This is what makes the dashboard tiles and the insight pages survive a device move: they are
 * computed from `usage_events`, so without these rows a restored backup shows a user with years of
 * rules and a lifetime "Blocked" count of zero.
 *
 * Screen time is NOT here and cannot be: it comes from `UsageStatsManager`, which is OS-owned and
 * re-derives itself per device. Only Nudge's own decisions transfer.
 */
data class ExportedHistoryEvent(
    val packageName: String,
    /** Epoch millis, as stored. */
    val timestamp: Long,
    val wasBlocked: Boolean,
    val blockMode: String?,
    val userChangedMind: Boolean
)
