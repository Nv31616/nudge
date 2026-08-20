package com.astraedus.nudge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One block/allow *decision* made by the engine — not a foreground-time sample.
 *
 * Screen time comes from `UsageStatsManager` via `ScreenTimeProvider`; this table only ever
 * answers "what did we decide, for which app, when". A `durationMs` column lived here until
 * issue #22 but was never written, so every consumer that summed it read 0 forever.
 */
@Entity(tableName = "usage_events")
data class UsageEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val wasBlocked: Boolean = false,
    val blockMode: String? = null,
    val userChangedMind: Boolean = false
)
