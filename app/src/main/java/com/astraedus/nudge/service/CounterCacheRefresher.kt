package com.astraedus.nudge.service

/**
 * Per-package snapshot of everything the accessibility hot path needs to know about a rule WITHOUT
 * touching the database. One entry exists for every package that needs *any* foreground awareness:
 * the interaction counter, the time-remaining overlay, or a time-based auto-kick.
 *
 * [showCounter] is what decides whether the floating interaction counter is drawn — it is NOT the
 * same question as "does this package have an entry". A rule can want a time-based auto-kick (or a
 * time-remaining overlay) with the counter switched off, and must not get a counter overlay it
 * never asked for.
 */
data class CounterCacheEntry(
    val showCounter: Boolean = false,
    val autoKickAfter: Int? = null,
    val showTimeRemaining: Boolean = false,
    val dailyLimitMinutes: Int? = null,
    val autoKickCooldownSeconds: Int = 60,
    /** Time-based auto-kick threshold in minutes of session foreground time. Null = disabled. */
    val autoKickAfterMinutes: Int? = null
) {
    /**
     * True when this package needs the periodic foreground-time tick — i.e. something here is driven
     * by a clock rather than by accessibility events. Without a tick, a passively-watched app
     * (zero taps, zero scrolls) produces no events and neither the time-remaining overlay nor the
     * time-based auto-kick would ever update.
     */
    val needsForegroundTimeTick: Boolean
        get() = autoKickAfterMinutes != null || (showTimeRemaining && dailyLimitMinutes != null)
}

class CounterCacheRefresher(
    private val refreshIntervalMs: Long = 10_000L
) {
    // Atomic reference swap -- readers never see a half-populated map
    @Volatile
    private var enabledPackages: Map<String, CounterCacheEntry> = emptyMap()
    @Volatile
    private var lastRefreshTime: Long = 0L

    /**
     * True when this package is TRACKED at all (counter, time-remaining overlay, or time-kick).
     * Use this for "should I keep foreground state for this app"; use [isCounterEnabled] for
     * "should I draw / feed the interaction counter".
     */
    fun hasEntry(packageName: String): Boolean = packageName in enabledPackages

    /** True only when the user actually asked for the floating interaction counter. */
    fun isCounterEnabled(packageName: String): Boolean =
        enabledPackages[packageName]?.showCounter == true

    fun getAutoKickAfter(packageName: String): Int? = enabledPackages[packageName]?.autoKickAfter

    fun getEntry(packageName: String): CounterCacheEntry? = enabledPackages[packageName]

    fun snapshot(): Set<String> = enabledPackages.keys

    suspend fun refreshIfNeeded(
        now: Long,
        loadEnabledPackages: suspend () -> Map<String, CounterCacheEntry>
    ): Boolean {
        if ((now - lastRefreshTime) < refreshIntervalMs) return false
        lastRefreshTime = now
        enabledPackages = loadEnabledPackages()
        return true
    }

    suspend fun forceRefresh(
        loadEnabledPackages: suspend () -> Map<String, CounterCacheEntry>
    ) {
        lastRefreshTime = System.currentTimeMillis()
        enabledPackages = loadEnabledPackages()
    }

    companion object {
        /**
         * Collapses every rule targeting the same package into one entry. Where rules disagree the
         * merge always takes the STRICTEST interpretation: the lowest kick threshold, the lowest
         * daily limit, the longest cooldown, and any rule asking for an overlay wins.
         */
        fun mergeEntries(
            entries: Iterable<Pair<String, CounterCacheEntry>>
        ): Map<String, CounterCacheEntry> {
            return entries
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .mapValues { (_, packageEntries) ->
                    CounterCacheEntry(
                        showCounter = packageEntries.any { it.showCounter },
                        autoKickAfter = packageEntries
                            .mapNotNull { it.autoKickAfter }
                            .minOrNull(),
                        showTimeRemaining = packageEntries.any { it.showTimeRemaining },
                        dailyLimitMinutes = packageEntries
                            .mapNotNull { it.dailyLimitMinutes }
                            .minOrNull(),
                        autoKickCooldownSeconds = packageEntries
                            .maxOf { it.autoKickCooldownSeconds },
                        autoKickAfterMinutes = packageEntries
                            .mapNotNull { it.autoKickAfterMinutes }
                            .minOrNull()
                    )
                }
        }
    }
}
