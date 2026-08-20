# Plan — Willpower + Interventions insight pages

Two new screens reached by tapping the home dashboard's "Walked Away" / "Blocked" tiles
(both the Today row and the All Time row).

## Data semantics (verified against the write paths, not assumed)

`NudgeAccessibilityService.handleDecision` logs ONE event per block decision at
overlay-show time (`wasBlocked=true, blockMode=X, userChangedMind=false`), and
`BlockOverlayActivity.navigateHome` logs a SECOND event when the user taps
"I changed my mind" (`wasBlocked=true, blockMode=X, userChangedMind=true`).
ALLOW decisions log `wasBlocked=false`.

    overlaysShown = wasBlocked && !userChangedMind
    walkAways     = userChangedMind

A walk-away can therefore exist without its paired show event (older row aged out,
or a show event that failed to write). The calculator never divides by a number it
did not derive from the same data: it uses

    attempts  = max(overlaysShown, walkAways)
    gaveIn    = attempts - walkAways          (>= 0 by construction)
    rate      = walkAways / attempts          (0 when attempts == 0)

so the rate can never exceed 100%, can never be NaN, and `walkAways + gaveIn == attempts`
holds in every section of both screens.

All-time overlay count = `allTimeBlocked - allTimeChangedMind` (clamped at 0), because
the existing all-time DAO count includes the walk-away duplicates. The HOME tiles keep
their current semantics — untouched.

## Layers

- `ui/screens/stats/InsightsCalculator.kt` — pure Kotlin. `ZoneId` + `nowMs` are
  parameters (never `System.currentTimeMillis()` / `ZoneId.systemDefault()` inside),
  so timezone and midnight behaviour are unit-testable. All bucketing via `java.time`
  in LOCAL time.
- `WillpowerViewModel` / `InterventionsViewModel` — `@HiltViewModel`, one
  `getEventsSince(30d)` flow + a range `StateFlow`, aggregated in memory. No new
  SQL aggregates.
- `charts/` — Canvas, Material 3 colorScheme only, no new dependencies. Five new
  reusable primitives (ring, rate/count bar chart, segmented bar, sparkline, 7x24 heatmap).

## Deliberate calls

- Weekly trend respects the range toggle: 7d yields one bucket, so the section renders
  a hint instead of a one-point "trend". No section silently ignores the toggle.
- Time reclaimed is `walkAways x avg session length per app`, avg derived from
  `ScreenTimeProvider` (`totalForegroundTime / sessionCount` over the range), clamped to
  [1min, 30min] and falling back to a labelled 5min default. Always rendered with "est.".
- Strongest/weakest hour only consider hours with >= 2 attempts, and weakest is
  suppressed when it is not strictly worse than strongest (tiny samples lie).
