package com.astraedus.nudge.domain.model

enum class BlockMode {
    /**
     * Blocks nothing on its own. Exists so an app-level rule can carry the daily limit, the
     * interaction counter and the time-remaining overlay WITHOUT also gating the whole app — which
     * is what makes "block only Shorts, leave the rest of YouTube alone" expressible.
     *
     * Web-domain blocking IS carried, since issue #21: a rule's websites enforce at their own
     * [com.astraedus.nudge.data.db.entity.BlockRule.webBlockMode] (resolved by [WebBlockMode]),
     * so "the app opens normally, the website is blocked" is expressible. Before that fix, web
     * domains were evaluated through THIS mode and so enforced nothing at all on a NONE rule —
     * a blocker silently not blocking — and the config screen disabled the web toggle to hide it.
     *
     * NOT carried: grayscale. It is enforced only through a `BlockDecision.Block`, and a NONE
     * rule yields Allow — so a NONE rule's grayscale flag is inert, applying only while some
     * OTHER rule (e.g. a feature override) is blocking. Fixing that needs a separate enforcement
     * path in the service: there is no "grayscale while allowing" decision today, and
     * `BlockDecision.Block` is the only thing `GrayscaleManager` is driven from.
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
