# Stats overhaul — day selection, intuitiveness, home graphs

## The reported bug, root-caused

`WeeklyBarChart` and `BlockedTrendChart` each own a **private** `var selectedIndex by remember { mutableStateOf<Int?>(null) }`.
Tapping a bar dims the other bars and prints a one-line tooltip — and that is *all* it does.
The screen's real day state lives in `StatsViewModel._selectedDate`, driven only by the
`DateNavigationRow` arrows. So the chart says "you picked Monday" while every number on the
screen (total card, hourly heatmap, per-app list, streak) still reads whatever the arrows
last pointed at. Two sources of truth for one question, and the louder one is inert.

Same defect, same two charts, on `AppDetailScreen`.

`InterventionsScreen` / `WillpowerScreen` are NOT affected: their `InsightsRangeToggle` really
does drive every section (`_range` → `InsightsCalculator`). `RateBarChart` / `HourlyHeatmap`
local selection is a pure readout with no drill-down target below it — that is legitimate.

## Fix: one selection, hoisted

Charts become **controlled**. New pure model, `StatsDaySelection`:

- `weekEnd` — last day of the 7-bar window.
- `selected` — the day whose numbers the screen shows. Always inside the window.
- Tapping bar *i* moves `selected` **without moving the window** (so bars don't slide out
  from under the finger).
- Arrows move `selected` by a day and slide the window by the minimum needed to keep it visible.
- `nextDay` is capped at today; `jumpToToday` resets both.

Everything about it is pure `java.time` → JVM-tested (`StatsDaySelectionTest`).

Bar hit-testing was duplicated in three charts with the same arithmetic. Extracted to
`ChartGeometry` (pure, tested), used by all of them.

## Intuitiveness

- Date row becomes a real header: day label + week-range subtitle + a **Today** button that
  only appears when you're not on today.
- Selected bar is drawn at full weight with a marker dot under a **bold** label; unselected
  bars dim. The tooltip stays.
- Section titles carry the window/day they describe ("Screen time · Last 7 days",
  "App usage · Yesterday") so a number can never be read against the wrong day.
- Home's "Screen Time" card was inert once permission was granted — now it opens Usage Stats.

## Home graphs

`HomeChartsBuilder` (pure) turns the weekly screen-time totals + the week's `usage_events`
into the two chart models. Home renders:

1. **Screen time · last 7 days** — `WeeklyBarChart`, non-selectable, whole card taps to Stats.
2. **Nudges · last 7 days** — `BlockedTrendChart` (blocked bars + walked-away line), same.

Cost: one extra `getDailyScreenTimesForWeek` per existing 30 s poll and one
`getEventsSince(weekStart)` Room flow. No new main-thread work.

While in there: `HomeViewModel` computed `todayStart` **once at construction**, so an app
left open past midnight kept showing yesterday's "Today" counts against a "Today" label —
the same class of defect as the reported one. Day boundary now re-derives on the poll tick.

## Tests

- `StatsDaySelectionTest` — every transition, window sliding both directions, today cap,
  index round-trip, label rules.
- `ChartGeometryTest` — bar index at edges/gaps/out-of-range.
- `HomeChartsBuilderTest` — week totals, trend counts, empty week.
- `StatsViewModelTest` — existing suite must stay green (anchor semantics unchanged).
