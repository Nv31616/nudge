package com.astraedus.nudge.domain.pip

/**
 * Pure "have we already explained picture-in-picture for this app?" bookkeeping (issue #19). No
 * Android dependencies, so it is fully JVM-unit-testable.
 *
 * Why a ledger at all: the PiP escape is a PLATFORM limitation Nudge cannot fix in code — the only
 * real remedy is the user turning the per-app PiP permission off in Settings. That means the
 * explainer is a one-shot piece of education, not an enforcement surface. Showing it every time an
 * app slipped into PiP would be pure nagging on a screen the user has already read and possibly
 * already acted on, so it is recorded per package and never repeated.
 *
 * Storage mirrors [com.astraedus.nudge.domain.emergency.EmergencyPass]: one serialized string in
 * DataStore rather than a `stringSetPreferencesKey`, because the set semantics we need (ordered,
 * capped, tolerant of garbage) are exactly what that pattern already gives us elsewhere in this app.
 *
 * [parse] is deliberately lenient — blank/garbage input yields an empty set, never an exception.
 * Failing soft here means "we have not prompted for anything yet", i.e. the user gets the
 * explanation once more. Failing hard would crash the accessibility hot path; failing soft in the
 * other direction (pretending everything is prompted) would silently kill the feature.
 */
object PipEscapeLedger {

    private const val SEPARATOR = ';'

    /**
     * Upper bound on remembered packages. A user cannot plausibly hit this with real apps, but the
     * ledger is written from an event-driven path, so it gets a ceiling rather than the chance to
     * grow without bound in prefs. Oldest entries are dropped first — the most recently explained
     * app is the one most worth not re-nagging about.
     */
    const val MAX_ENTRIES: Int = 200

    /** Deserialize. Blank/garbage input → empty set. Order is preserved (oldest first). Never throws. */
    fun parse(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.split(SEPARATOR)
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toCollection(LinkedHashSet())
    }

    /** Inverse of [parse]. Round-trips; blank entries are dropped defensively. */
    fun serialize(packages: Set<String>): String =
        packages.filter { it.isNotBlank() }.joinToString(SEPARATOR.toString())

    /**
     * The ledger after recording that [packageName] has been explained. Idempotent: re-recording an
     * already-present package returns the set unchanged (so a duplicate write cannot rotate a
     * still-relevant entry out via the [MAX_ENTRIES] cap).
     */
    fun record(prompted: Set<String>, packageName: String): Set<String> {
        if (packageName.isBlank() || packageName in prompted) return prompted
        val next = LinkedHashSet<String>(prompted)
        next.add(packageName)
        if (next.size <= MAX_ENTRIES) return next
        return next.drop(next.size - MAX_ENTRIES).toCollection(LinkedHashSet())
    }
}
