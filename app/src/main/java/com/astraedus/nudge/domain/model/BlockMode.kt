package com.astraedus.nudge.domain.model

enum class BlockMode {
    /**
     * Blocks nothing on its own. Exists so an app-level rule can carry the daily limit, the
     * interaction counter and the time-remaining overlay WITHOUT also gating the whole app — which
     * is what makes "block only Shorts, leave the rest of YouTube alone" expressible.
     *
     * NOT carried: grayscale and web-domain blocking. Both are enforced only through a
     * `BlockDecision.Block`, and a NONE rule yields Allow — so grayscale applies only while some
     * OTHER rule (e.g. a feature override) is blocking, and a web-domain rule whose mode is NONE
     * allows the site. The config screen disables the web toggle in that state rather than
     * offering protection that would never be enforced.
     *
     * Before this existed, [com.astraedus.nudge.ui.screens.config.UnifiedAppConfigScreen] always
     * wrote a blocking app-level rule and feature overrides could only differ from it, so a
     * feature-scoped rule with an unblocked host app could not be configured at all.
     *
     * [com.astraedus.nudge.domain.engine.BlockEngine] matches NONE in none of its block branches,
     * so it yields Allow. A daily limit on a NONE rule is still enforced — the time-budget check
     * keys off `dailyLimitMinutes`, not the mode — which is deliberate: "don't block it, but cap
     * it at 60 min/day" is a real thing users want.
     */
    NONE,
    HARD_BLOCK,
    DELAY,
    BREATHING
}
