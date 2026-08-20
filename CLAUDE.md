# Nudge — Open-Source Android App Blocker

Privacy-first app blocker with delay-to-open (breathing exercises before opening distracting apps), per-app daily time budgets, app groups, schedule-based rules, in-app feature blocking (YouTube Shorts, Instagram Reels, TikTok), and grayscale mode. Zero internet permission. All data local.

- GitHub: https://github.com/astraedus/nudge
- F-Droid MR: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/38398
- v1.10.0 (current)
- See CHANGELOG.md for release history

## Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug                    # Build debug APK
./gradlew assembleRelease                  # Build release APK (needs keystore.properties)
./gradlew test                             # Unit tests (JVM)
./gradlew connectedAndroidTest             # Instrumented tests (needs device)
adb install -r app/build/outputs/apk/debug/app-debug.apk  # Install on device
```

Test device: Pixel 3 on ADB at `192.168.1.68:5555` (Android 12, API 31).

**Gradle version: stay on 8.x.** Do NOT upgrade to Gradle 9.x -- it removed `JvmVendorSpec.IBM_SEMERU` which the React Native / Android Gradle plugins still reference. Gradle 8.13 is the latest compatible version. Currently on 8.7.

## Releasing

Two paths: fast (instant) or CI (verified).

**Fast path** -- release is live in seconds, CI verifies in the background:
```bash
# 1. Bump version in app/build.gradle.kts (versionCode + versionName)
# 2. Build locally: ./gradlew test && ./gradlew assembleRelease
# 3. Commit, tag, push
git add app/build.gradle.kts
git commit -m "chore: bump version to 1.4.0"
git tag v1.4.0
git push origin main --tags
# 4. Create release immediately with local release APK
cp app/build/outputs/apk/release/app-release.apk nudge-v1.4.0.apk
gh release create v1.4.0 nudge-v1.4.0.apk --title "v1.4.0" --generate-notes
```

**CI-only path** -- just tag and push, wait ~4 min for GitHub Actions:
```bash
git tag v1.4.0
git push origin main --tags
# GitHub Action builds release APK, tests, creates release automatically
```

CI runs on every tag push (`.github/workflows/release.yml`). Builds `assembleRelease` (APK) **and `bundleRelease` (AAB)** using secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`); both are attached to the GitHub Release (APK for direct download + F-Droid; AAB for Google Play). Also exposes `workflow_dispatch` so a Play-ready AAB can be rebuilt from `main` without re-tagging. If a release already exists (fast path), CI updates it.

**Every push to `main`** (added 2026-07-05) also auto-builds the signed APK (tests-gated by `./gradlew test`) and publishes/refreshes a rolling **`main-latest` PRERELEASE** (`…/releases/tag/main-latest`, always the newest main) — an installable dev build per merge, asset `nudge-main.apk`. NOT a versioned release; `v*` tags remain the real releases. Watch: `gh run list --repo astraedus/nudge --branch main`.

### Google Play release (catch Play up after a GitHub release)

GitHub releases are automatic; **Google Play is a separate, deliberate step** that runs **from the laptop, not CI**:

```bash
# Default: live to 100% of production users. This is the standing default.
scripts/publish-to-play.sh 1.7.0
# Opt in to a staged, halt-able rollout — only for genuinely risky releases,
# and you then OWE the promote step (see below).
STATUS=inProgress ROLLOUT=0.2 scripts/publish-to-play.sh 1.7.0
# Upload as a production DRAFT (no users affected) to eyeball it first.
STATUS=draft scripts/publish-to-play.sh 1.7.0
```

**Full rollout is the default (Anti, 2026-07-30: "for google play it's just easier that way").** A staged rollout is a second owed step days later, and the promotion can't be done by this script — so twice running (v1.9.4, v1.10.0) the tail step was forgotten or cost time, for ~no signal at our install base. Stage only when a release is genuinely risky, and file the promote as a dated task when you do.

**Promoting a staged/draft release later** — `publish-to-play.sh` CANNOT do it (`gplay release` re-uploads the AAB; Play rejects an existing versionCode), and **`gplay rollout complete` is also broken** — it sets `status=completed` but leaves `userFraction`, which Play rejects with `COMPLETED release must not have fraction`. Use the edit cycle:

```bash
EDIT=$(gplay edits create --package dev.astraedus.nudge | jq -r '.id')
gplay tracks get --package dev.astraedus.nudge --edit "$EDIT" --track production \
  | jq '[ .releases[] | select(.name=="X.Y.Z") | (.status="completed" | del(.userFraction)) ]' > /tmp/rel.json
gplay tracks update --package dev.astraedus.nudge --edit "$EDIT" --track production --releases @/tmp/rel.json
gplay edits validate --package dev.astraedus.nudge --edit "$EDIT"
gplay edits commit   --package dev.astraedus.nudge --edit "$EDIT"
gplay status --package dev.astraedus.nudge --pretty   # verify
```
Submit ONLY the release being promoted — the superseded one drops off the track automatically.

The script pulls the CI-built AAB from the GitHub Release (or `SOURCE=run` for a `workflow_dispatch` artifact), runs `gplay preflight` (offline secret/compliance scan), then `gplay release` to the chosen track with release notes auto-extracted from `CHANGELOG.md`.

**Why local, not CI (open-source security):** the repo is PUBLIC, so we never put the Google Play API credential in GitHub Actions — a malicious PR or compromised action could exfiltrate it. CI only ever holds the **upload key** (`KEYSTORE_BASE64`), and because Nudge is enrolled in **Play App Signing** (mandatory for apps first published after Aug 2021), Google holds the real app-signing key — a leaked upload key can be rotated in Play Console without bricking installed users. The powerful `gplay` admin service-account key stays on the laptop (chmod 600, gitignored). To go fully tag-triggered later, create a **dedicated, least-privilege** Play service account (Nudge-only, "release manager" — never the account admin key) and store it as a GitHub secret; only then is CI-side Play upload acceptable. Ref: `~/ops/references/play-console-cli.md`.

> Play track state is queryable: `gplay status --package dev.astraedus.nudge --pretty`. As of the AAB-pipeline addition, Play production was on 1.5.6 (versionCode 27) while the repo was at 1.7.0 (versionCode 31) — i.e. Play had drifted 4 versions behind because the push was manual. This script closes that gap.

## Release Signing

Keystore: `nudge-release.keystore` (PKCS12, alias `nudge`, 2048-bit RSA, 10000-day validity).
Config: `keystore.properties` (gitignored). CI uses GitHub secrets.
**Always use `assembleRelease`** for distribution. Debug APKs use machine-specific keys and cause "App not installed" when users try to update from a different build.

## Architecture

Clean Architecture in a single module with package boundaries:

```
com.astraedus.nudge/
├── data/           # Room DB, DAOs, repositories, DataStore preferences
├── domain/         # Pure Kotlin models, BlockEngine, use cases (NO Android deps)
├── service/        # AccessibilityService, ForegroundService, GrayscaleManager
├── ui/             # Jetpack Compose screens, navigation, overlay, theme
└── di/             # Hilt modules
```

**Dependency direction**: `ui` -> `domain` <- `data`, `service` -> `domain`. Domain has no Android imports.

### Core flow

```
AccessibilityService: TYPE_WINDOW_STATE_CHANGED
  (or a state-verified TYPE_WINDOW_CONTENT_CHANGED — see "Content-change app-switch fallback")
  -> BlockEngine.evaluate(packageName, time, usage)
  -> BlockDecision: ALLOW | HARD_BLOCK | DELAY | BREATHING
  -> Launch BlockOverlayActivity if not ALLOW
```

## Stack

- Kotlin, Jetpack Compose, Material 3
- Hilt (DI), Room (DB), DataStore (preferences)
- Coroutines + Flow
- Min SDK 26, Target SDK 34, Compile SDK 34

## Key conventions

- Domain layer is pure Kotlin — no `android.*` imports. Fully unit-testable on JVM.
- Single Activity architecture (MainActivity) + Compose Navigation.
- BlockOverlayActivity is a separate activity with `singleInstance` launch mode, `excludeFromRecents`, empty `taskAffinity`.
- AccessibilityService handles foreground app detection. ForegroundService keeps monitoring alive.
- All entities use Room `@Entity` annotations. DAOs return `Flow<>` for reactive queries.
- ViewModels use `@HiltViewModel` and inject use cases/repositories.
- No internet permission. No analytics. No telemetry.

## Block modes

- `HARD_BLOCK` — cannot open the app at all
- `DELAY` — configurable countdown (5/15/30/60s) before app opens
- `BREATHING` — guided breathing exercise before app opens (the signature feature)

## v1.1 Features

- **Schedule-based rules** — day-of-week + time-of-day, overnight schedule support (spans midnight)
- **In-app feature blocking** — YouTube Shorts, Instagram Reels/Explore, TikTok detection via AccessibilityService
- **Grayscale mode** — force screen to grayscale (requires ADB: `adb shell pm grant com.astraedus.nudge android.permission.WRITE_SECURE_SETTINGS`). Grayscale guide in Settings.
- **Rotating motivational messages** — shown on overlay screens when blocks trigger. **User-editable (v1.6.0)**: defaults live in `ui/overlay/NudgeMessages.kt` (delayTitles/delaySubtitles/hardBlockMessages); users override via Settings → Personalize → "Edit block messages" (`ui/screens/settings/MessagesEditorScreen.kt`), stored as 3 multiline strings in `NudgePreferences` (`customDelayTitles`/`customDelaySubtitles`/`customHardBlockMessages`, one message per line, empty = defaults). `NudgeMessages.resolvePool(customRaw, default)` is the pure resolver; `BlockOverlayActivity` reads the prefs once via `runBlocking{ first() }` before `setContent` (avoids a default→custom flash) and passes resolved pools into the overlay composables (which still `remember { pool.random() }`).
- **"Walked Away" tracking** — counts when user taps "I changed my mind" instead of waiting
- **2x2 dashboard stats** — Screen Time, Active Rules (tappable), Blocked, Walked Away
- **Floating interaction counter** — centered touch-through overlay (40sp counter, 16sp label, 13sp daily) showing reels/shorts scrolled or taps per session. Escalating colors: white (0-9), orange (10-19), deep orange (20-29), red with red background tint (30+). TYPE_ACCESSIBILITY_OVERLAY from service, no extra permission. Per-rule `showCounter` toggle (default ON for new rules).
- **Time remaining overlay** — per-rule opt-in (`showTimeRemaining`). Displays "42m left" or "1h 12m left" below counter, color-coded: green (>50% remaining), orange (25-50%), red (<25%). Uses UsageStatsManager for actual foreground time. Requires daily limit to be set.
- **Auto-kick** — optional per-rule feature: sends user to home screen after N scrolls/taps in one session. Configurable threshold 5-100 (step 5, default 30). Session counter resets after kick. Stored as `autoKickAfter` on BlockRule. Requires the interaction counter (it is what feeds the count).
- **Auto-kick by time (v1.10.0)** — the second trigger, per-rule `autoKickAfterMinutes` (null = off). Kicks after N minutes of foreground time in one session. Independent of the interaction trigger — both can be set, whichever fires first kicks — and independent of the interaction counter, because its whole point is PASSIVE use (autoplaying video produces zero tap/scroll events). See "Time-based auto-kick architecture".
- **Auto-kick cooldown** — configurable per-rule, stored as `autoKickCooldownSeconds` on BlockRule. After auto-kick, returning to the app forces a DELAY overlay for the remaining cooldown. Session counter preserved during cooldown. **v1.10.0**: the 0-300s slider became a free-form MINUTES input (0-1440), so issue #6's "30 minutes on, 15 minutes off" is expressible. See "Duration inputs".
- **Instagram home feed detection** — `InAppDetector` now detects Instagram's home feed (when Home tab is selected, no other tabs active) and treats it as REELS-equivalent. Home feed scrolling counts toward interaction counter and auto-kick the same as the Reels tab.
- **Post-overlay passthrough** — after delay/breathing completes, skip re-evaluation until user leaves app. Prevents infinite overlay loop.
- **Web domain blocking (Chrome v1)** — blocks websites in Chrome that match app rules. When Chrome is foregrounded, reads URL bar via accessibility tree (`WebDomainDetector`), extracts domain (`WebDomainMatcher`), matches against rules' `webDomains` field. Same overlay modes (HARD_BLOCK/DELAY/BREATHING), at the rule's own web mode (see below). Passthrough prevents re-blocking same domain. Only Chrome for v1 (extensible via `BROWSER_PACKAGES`). UI toggle "Block on web too" auto-populates known domains (Instagram, YouTube, TikTok) or allows custom entry.
- **Rule editor UX** — info tooltips on all sections, block mode descriptions, per-app rules summary with enable/disable
- **Settings** — version links to GitHub repo, source code & feedback link

## Database

Room DB version 9. Migrations: 1->2 (schedule/inapp/grayscale), 2->3 (userChangedMind), 3->4 (showCounter), 4->5 (autoKickAfter), 5->6 (showTimeRemaining, autoKickCooldownSeconds), 6->7 (webDomains), 7->8 (autoKickAfterMinutes), **8->9 (DROPS the dead `usage_events.durationMs` column — issue #22)**.

8->9 is the only migration that is not an `ALTER TABLE … ADD COLUMN`: SQLite before 3.35 has no `DROP COLUMN` and minSdk 26 ships far older engines, so it is a create/copy/drop/rename recreate of `usage_events`. Two constraints on anyone touching it — the recreated table must match Room's generated schema for `UsageEvent` byte for byte (`` `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL ``, …) or Room's validation throws on the next open; and the ROWS are user data (the block/allow history behind the stats screen), so they are copied across, never dropped. The column itself had no write path at all — every reader that summed it read 0 forever, which is how the #14 daily-limit bug shipped — so `NudgeDatabaseMigrationTest` also asserts reflectively that `UsageEvent` declares no duration-shaped field, and it cannot come back.

`NudgeDatabaseMigrationTest` is a **JVM** test (a `SupportSQLiteDatabase` `Proxy` records the `execSQL` calls), not an instrumented one — so migrations are gated by `./gradlew test` with no device. It also asserts every version gap from 1 to the current version has a registered migration, which is what catches "bumped the version, forgot `DatabaseModule.addMigrations`".

## Counter overlay architecture

- `InteractionTracker` (@Singleton): in-memory session/daily counts per package. No DB writes per interaction. Also tracks cooldown state per package after auto-kick.
- `CounterOverlayManager` (@Singleton): WindowManager overlay using service context (required for TYPE_ACCESSIBILITY_OVERLAY token). `setServiceContext()` called in `onServiceConnected()`. Centered on screen with escalating colors (white -> orange -> deep orange -> red) based on session count.
- `TimeRemainingOverlayManager` (@Singleton): Standalone floating overlay in top-right corner. Shows "Xm left" with color-coded text (green >50%, orange 25-50%, red <25%) and increasingly opaque background. Separate from counter overlay so both can show independently.
- `activeReelLabel`: once Shorts/Reels feature detected, skip tree inspection on subsequent scrolls. Reset on app switch.
- Tracked packages cached every 10s via `CounterCacheRefresher` (Map<String, CounterCacheEntry> with showCounter, autoKickAfter, showTimeRemaining, dailyLimitMinutes, autoKickCooldownSeconds, autoKickAfterMinutes per package). A rule enters the cache if it wants **any** of: the counter, the time-remaining overlay, or a time-based auto-kick. `mergeEntries` collapses multiple rules per package to the strictest reading (lowest thresholds, longest cooldown, any overlay wins).
- **`hasEntry` vs `isCounterEnabled` (v1.10.0)** — these are different questions and conflating them is a bug. `hasEntry` = "this package is tracked at all" (drives foreground/session bookkeeping); `isCounterEnabled` = `showCounter`, and is the ONLY thing that may draw or feed the interaction counter. Before the split, cache membership implied a counter, so a rule that only wanted a time-based kick (or only the time-remaining overlay) would have switched on a floating tap counter the user never asked for. Guarded by `InteractionHandlerTest."a package tracked only for a time-based kick gets no counter overlay"`.
- Auto-kick: two triggers, ONE kick. `AutoKickExecutor.kick(pkg, reason)` is the single place the kick happens — arm cooldown, go home, `resetSession`, hide the counter — so the interaction trigger (`InteractionHandler`) and the time trigger (`AutoKickTimeHandler`) can never drift in what they do to the user. It takes a `goHome` lambda rather than building the Intent itself, which keeps the policy JVM-testable and lets the service prefer `requestGoHome()` (accessibility `GLOBAL_ACTION_HOME`) over a HOME intent, as `EmergencyPassManager` already does.
- Auto-kick cooldown: configurable per-rule (default 60s). After auto-kick, re-opening the app shows a DELAY overlay for the remaining cooldown. Session counter NOT reset during cooldown.
- Time remaining overlay: optional per-rule (`showTimeRemaining`). Uses UsageStatsManager to get actual foreground time, displays remaining daily limit as color-coded overlay line.

## Time-based auto-kick + the foreground-time clock — v1.10.0 (fixes #6)

Fix for [#6](https://github.com/astraedus/nudge/issues/6): "kick you out of the app on a timer and then lock the app for x amount of time … use an app for 30 minutes, then block it for 15 minutes."

Built by EXTENDING the auto-kick machinery, not beside it: the new trigger feeds the same `AutoKickExecutor`, the same `autoKickCooldownSeconds`, the same cooldown DELAY overlay on re-entry, and the same session reset.

**The clock had to be built.** The pre-existing "30s updates" of the time-remaining overlay were a 30s *debounce* on event-driven calls (`TimeRemainingHandler.maybeUpdate` was only reached from an interaction or an app-entry) — there was no timer anywhere in the service. That is fine for counting taps and useless for passive watching, which is exactly the case #6 is about. It also meant the **daily-limit HARD_BLOCK could be late** for a passively-watched app; driving it from the new tick fixes that too.

- **`domain/autokick/TimeKickEvaluator.kt`** — pure Kotlin. `evaluate(thresholdMinutes, baselineUsageMs, currentUsageMs)` -> `DISABLED | START_SESSION | WAIT | REBASELINE | KICK`. A null/0/negative threshold is DISABLED (a stored 0 must never mean "kick after 0 minutes"); a reading BELOW the baseline is REBASELINE, not a kick (the daily total resets at midnight, and a negative elapsed time is not evidence of overstaying).
- **The session marker** lives in `InteractionTracker` alongside the interaction count: `sessionUsageBaseline[pkg]`, the `UsageProvider.getDailyForegroundTimeMs` reading taken when the session began. Elapsed session time = current reading − baseline. This choice does the work for free: `getDailyForegroundTimeMs` sums ACTIVITY_RESUMED→PAUSED spans, so **time in other apps and time with the screen off are simply not in the reading** — no wall-clock bookkeeping, no stint accounting.
- **Session semantics — deliberately identical to the interaction counter's.** The baseline is cleared in exactly the same branches that zero `sessionCounts`: on `onAppChanged` when the user has been away ≥ `SESSION_EXPIRY_MS` (5 min) and is not in cooldown, and on `resetSession` (which the kick calls). So a quick tab-out-and-back CONTINUES the budget (closing the obvious bypass), a real break restarts it, and the two triggers can never disagree about whether this is still the same sitting. Pinned by `InteractionTrackerTest` + `AutoKickTimeHandlerTest`.
- **`service/AutoKickTimeHandler.kt`** — reads the clock, advances/repairs the baseline, returns whether to kick. Deliberately does NOT kick: it runs off-main (the usage read is a binder call) while the kick touches the WindowManager, so the caller hops to Main. A failing usage read returns false — an unreadable clock must never eject a user.
- **`NudgeAccessibilityService.updateForegroundTimeTicker(pkg)`** — starts one 30s coroutine per foreground app, but ONLY when `CounterCacheEntry.needsForegroundTimeTick` (a minutes threshold, or time-remaining with a daily limit); a counter-only package spins no timer. Idempotent per package, because `evaluateForegroundPackage` is re-entered on debounced events and the issue #7 content-change fallback — restarting the job each time would keep resetting the `delay` and the clock would never tick. Started **before** the emergency-pass / cooldown / passthrough early-returns (a user who just completed a delay is precisely who this is for); stopped in `clearOverlays`, `hideAllOverlays`, `onDestroy` and immediately after a kick.
- **Each tick** re-checks `globalEnabledCached` and `EmergencyPassManager.isPassActive` (a timer is not covered by the synchronous event gate, and the daily pass promises uninterrupted minutes), then feeds the kick check and `timeRemainingHandler.maybeUpdate`.
- **Granularity**: a kick can overshoot its threshold by up to one tick (30s). Acceptable against thresholds measured in minutes, and cheaper than a tighter poll on the 3GB Pixel 3.
- **Scope**: time-kick is APP-level only — no per-feature (Reels/Shorts) minutes input, because the cache is keyed by package and a per-feature threshold would leak to the whole app.
- **Tests**: `TimeKickEvaluatorTest` (every branch incl. zero-threshold and midnight rollover), `AutoKickTimeHandlerTest` (baseline lifecycle, quick-return does not reset, real break does, cross-package isolation, failing read never kicks), `CounterCacheRefresherMergeTest` (merge + `needsForegroundTimeTick`), `InteractionHandlerTest` (shared executor; time-kick-only package gets no counter).

## Duration inputs (`ui/components/DurationInput.kt`) — v1.10.0

The auto-kick cooldown and the new minutes threshold are free-form numeric fields in MINUTES (0-1440), replacing the old 0-300s slider. The **UI state holds the raw String**, not an Int, so a blank field round-trips as blank ("off") instead of snapping back to "0".

- Storage is unchanged: `autoKickCooldownSeconds` is still seconds. `DurationInput` owns the one conversion.
- **Display rounds UP** (`cooldownSecondsToText`): the old slider was `steps = 5` over `0f..300f`, which Compose resolves to the seven stops **0/50/100/150/200/250/300** — the old code comment claiming 0/60/120/… was wrong — so 50s and 150s cooldowns exist in the wild and a minutes field can only show them rounded. Rounding down could shorten protection; 50s showing as "0" would read as off.
- **Rounding never reaches storage.** `resolveCooldownSeconds(text, originalSeconds)` / `resolveMinutes(text, originalMinutes)` return the ORIGINAL value verbatim when the field still reads what was rendered for it, so a save that did not touch the field re-persists the exact prior value. Both editors carry `original…` fields in state for this. It matters because `RuleWeakening` treats a lowered cooldown as a weakening: without it, opening an editor and saving an unrelated change could rewrite 150s→180s and raise a spurious Strict Mode challenge later.
- Turning auto-kick off PRESERVES the stored cooldown (it used to snap back to 60s) for the same reason.
- `RuleEditorViewModel.buildRule` is `internal` and pure so the whole save contract is JVM-testable (`RuleEditorRuleBuilderTest`) — including the regression that this editor used to drop `webDomains` on every save.

## Web domain blocking architecture

- `domain/WebDomainMatcher.kt` — pure Kotlin (no Android deps). `extractDomain(urlBarText)` strips protocol/path/port, normalizes subdomains (www, m, mobile, l, lm). `matches(urlBarText, webDomains)` checks extracted domain against comma-separated rule domains.
- `service/WebDomainDetector.kt` — `@Singleton`, two-strategy URL-bar read. Multi-browser: `BROWSER_URL_BAR_IDS` maps each package to ordered candidate view-id suffixes (Chrome/Brave/Edge/Kiwi `url_bar`+`omnibox_url_text`, Firefox/Fenix `ADDRESSBAR_URL_BOX`+`mozac_browser_toolbar_url_view`, Samsung Internet `location_bar_edit_text`, Opera `url_field`, DuckDuckGo `omnibarTextInput`). **Strategy 1 (fast path)**: `findAccessibilityNodeInfosByViewId()` over fully-qualified `pkg:id/suffix` ids (Chromium family, Samsung, Opera, DDG). **Strategy 2 (traversal fallback)**: when the fast path finds nothing, `findNodeByViewId()` does a bounded (≤600 nodes) DFS matching `node.viewIdResourceName` against the BARE suffixes — needed for modern Firefox, whose Compose toolbar exposes the URL bar as a bare testTag `ADDRESSBAR_URL_BOX` (no `pkg:id/` prefix) that `findAccessibilityNodeInfosByViewId` will NOT match at runtime, with the URL in `contentDescription` (not `text`). `readUrlRaw(node)` reads text→contentDescription; `cleanAddressBarText()` strips Firefox's localized "…. Search or enter address" hint by cutting at the first `\.\s` (locale-agnostic; URLs never contain period-space). `urlBarViewIdsFor`/`qualifiedUrlBarViewIdsFor`/`bareUrlBarViewIdsFor` are pure, unit-tested. `isBrowser(pkg)` checks map membership. Findings/rationale: `docs/firefox-webblock-findings.md`.
- Integration in `NudgeAccessibilityService`: on `TYPE_WINDOW_STATE_CHANGED`/`TYPE_WINDOW_CONTENT_CHANGED` for browser packages, calls `evaluateWebDomain()` which reads URL bar, extracts domain, queries `EvaluateBlockUseCase.evaluateWebDomain()`.
- `EvaluateBlockUseCase.evaluateWebDomain()` finds all enabled rules with matching `webDomains`, resolves each one's WEB mode, converts to `ActiveRule` list, passes through `BlockEngine`.
- **Web enforcement is independent of the app-level mode (fixes [#21](https://github.com/astraedus/nudge/issues/21))**. Web domains used to be evaluated with `BlockMode.valueOf(rule.mode)`, the APP-level mode, so a rule with `BlockMode.NONE` (whole-app blocking off, the state that makes Shorts-only blocking expressible) allowed every configured website too: a blocker silently blocking nothing, the worst failure class this app has. It was mitigated in the UI only, by disabling the "Block on web too" toggle in that state.
  - `BlockRule.webBlockMode` (nullable, DB v10) is the independent mode. **NULL = inherit the app-level mode**, which is exactly what every pre-v10 rule did, so existing behaviour is untouched wherever the app mode is a real blocking mode.
  - `domain/model/WebBlockMode.resolve(ruleMode, webBlockMode)` is the ONE resolver (pure, `WebBlockModeTest`): the web mode wins, else the app mode, else HARD_BLOCK (fail toward enforcement, matching how a corrupt mode string was already handled). Never read the column raw.
  - A rule whose web mode resolves to NONE is DROPPED from the match list rather than yielding a no-op Block, so a URL covered only by such rules still falls through to the generic content filter instead of being treated as "handled, allowed".
  - `MIGRATION_9_10` repairs the rows the bug created (`mode='NONE'` + non-empty `webDomains`) to `webBlockMode='DELAY'`, those users had opted into web blocking and were getting nothing. DELAY, not HARD_BLOCK: the rule already carries a `delaySeconds`, and it matches the editor's own fallback when no prior blocking choice exists.
  - `RuleWeakening` gained the web axis (softened web mode, or domains removed while previously set), otherwise Strict Mode could be sidestepped by weakening web enforcement while leaving the app-level rule alone.
  - `RuleEditorViewModel` carries `webBlockMode` through its rebuild, same reason it already carries `webDomains`: that editor has no web UI and replaces the loaded rule wholesale.
  - **Grayscale is still NOT carried by a NONE rule**, it rides only inside `BlockDecision.Block`, so a NONE rule's `grayscale` flag is inert (documented on `BlockMode.NONE`). Fixing it needs a separate "apply grayscale while allowing" path in the service; out of scope for #21.
  - Tests: `WebBlockModeTest` (resolution matrix), `EvaluateBlockWebDomainTest` (the NONE regression, real modes unchanged, override, content-filter fallthrough, trackingPackage/ruleName), `NudgeDatabaseMigrationTest` (ALTER + repair UPDATE), `RuleWeakeningTest` (web axis both directions), `RuleExporterTest` (round-trip + older exports), `UnifiedAppConfigViewModelTest`, `RuleEditorRuleBuilderTest`.
- Passthrough: `lastBlockedDomain` tracks currently-blocked domain. Same domain won't re-trigger until user navigates away. Clears on app switch away from browser.
- UI: "Block on web too" toggle in `UnifiedAppConfigScreen`, auto-populates known domains per `DEFAULT_WEB_DOMAINS` map. It is available **regardless** of whole-app blocking (issue #21). With whole-app blocking ON the websites follow the app's mode (`webBlockMode` persisted as null, and `setDefaultMode` keeps the web picker in sync so switching whole-app blocking off later doesn't silently change what the sites do); with it OFF the section shows a "Website block mode" segmented picker whose value IS persisted. The shared `delaySeconds` control (`UnifiedAppConfigState.showDelayDuration`) stays reachable in the web-only case, else a web DELAY would be stuck at whatever was last saved.

## Content filter architecture (generic "restricted content" web blocker)

Generic, opt-in master switch that blocks websites against a large bundled blocklist + keyword list across supported browsers. **Framing constraint: NOTHING user-facing reveals the adult/restricted-content purpose** — the blocklist and keywords live only in code/assets. UI strings say "Block restricted websites" / overlay rule name "Restricted content".

- `app/src/main/assets/content_filter_domains.txt` — **486 hand-curated** newline-separated lowercased base domains (12KB), plus `#` comment lines carrying the curation policy. Packaged into the APK.
- **The 274k-blob incident (v1.10.1) — why the list is curated, and why it must stay that way.** The asset was originally a ~274,642-domain / 4.5MB upstream blob. It was flatly wrong about real sites: `virginia.gov` (a US state government portal), `purdue.edu`, `rice.edu`, `ku.edu`, `metrostate.edu`, `ohiochristian.edu`, `itu.int` and `utwente.nl` were all in it. Worse, because `matchesDomain` walks parent domains, its entries for `amazonaws.com`, `cloudfront.net`, `wordpress.com`, `blogspot.com`, `myshopify.com` and `appspot.com` silently blocked **every site hosted on those platforms**. It had already forced the `reddit.com` ALLOWLIST guard in v1.9.2 — that was the same defect surfacing once, treated as a one-off. **Curation policy (also written into the asset header): precision over recall — a false positive on a government or university site is far worse than missing adult site #4000.** Every entry must be an individually justifiable, recognisable, purpose-built adult site. The domain list's real job is the sites the keyword layer *cannot* see (bangbros, beeg, e621, missav, coomer, literotica…) since any domain containing `porn`/`xxx`/`hentai`/`xvideos`/… is already caught by `matchesKeyword`; the majors are listed anyway as cheap defence in depth. Deliberately **excluded**: CDNs/ad networks/hosting (the filter reads the URL bar — nobody navigates to a CDN, so they add zero blocking value and pure false-positive risk), dating/hookup apps, sex-education and health resources, sexual-wellness retail, mainstream creator/art platforms (patreon, deviantart, itch.io, dlsite, dmm), fan-fiction archives, modelling-industry sites.
- `data/repository/ContentFilterRepository.kt` (`@Singleton`, impl of `ContentFilter` interface) — lazily loads the asset into an in-memory `HashSet<String>` on **first** `isBlocked()` call (not at app/service start), on `Dispatchers.IO`, guarded by a `Mutex` so concurrent callers load once. `parseLine()` (`internal`, in the companion) skips blank and `#` lines and is what the asset test uses, so the test parses exactly the bytes the app does. Fails open to empty set if the asset is unreadable. The `ContentFilter` interface lets `EvaluateBlockUseCase` be unit-tested without loading the asset.
- `domain/ContentFilterMatcher.kt` — pure Kotlin. `matchesDomain(url, blocklist)` extracts the base domain via `WebDomainMatcher.extractDomain` then checks it + progressively-stripped parent domains against the set (subdomains of a blocked base match). **`ALLOWLIST` regression guard (v1.9.2)**: mainstream mixed-content platforms (reddit.com, redd.it, redditmedia/static, twitter.com, x.com, imgur.com, discord.com/discordapp.com, tumblr.com, wikipedia.org) are checked via `isAllowlisted(host)` **before** the blocklist and win unconditionally — so a regenerated blocklist re-introducing reddit.com (the upstream list wrongly included it) can never re-block plain Reddit browsing. The allowlist exempts the **domain match only**; `matchesKeyword` on the raw URL is unaffected (reddit.com/r/porn still blocks via keyword — correct). reddit.com + tumblr.com were also physically removed from the asset. `matchesKeyword(url, keywords)` does case-insensitive substring matching of `DEFAULT_KEYWORDS` against the raw URL (catches search queries + unknown domains). Keyword list deliberately avoids short ambiguous tokens (no bare "sex"/"anal") to prevent false positives like sussex/essex/analysis. **v1.10.1 pruned four more (same incident as the blob replacement):** "escort" (ford escort, police escort, escort carrier, Escort radar), "hardcore" (hardcore punk, Hardcore History, hardcore difficulty) and "creampie" (recipe slugs) are gone from **both** lists — demoting them to `AMBIGUOUS_QUERY_KEYWORDS` would not help, because a whole-word query match still fires on exactly the benign searches being protected ("ford escort review", "hardcore punk bands", "creampie recipe"). "fetish" WAS demoted to `AMBIGUOUS_QUERY_KEYWORDS`: as a raw substring it blocked `wikipedia.org/wiki/Commodity_fetishism`, but whole-word-in-a-query it is a strong signal and does not collide with "fetishism"/"fetishist". Escort-directory coverage moved from the keyword to the curated domain list. Membership test for any new token: **would this plausibly appear in a benign URL, or in a search a normal person makes?**
- **Query-scoped matching (v1.8.0)** — for ambiguous shorthand tokens (`AMBIGUOUS_QUERY_KEYWORDS`, e.g. "bbc") that would be dangerous as raw substrings (would block bbc.com news), `matchesQueryKeyword(url, keywords)` matches them as WHOLE WORDS inside the URL's SEARCH QUERY only (never the host). `extractSearchQuery(url)` is a hand-rolled (NOT android.net.Uri, stays JVM-pure) parser that pulls decoded search terms from common param names (`q`/`query`/`search_query`/`p`/`text`/`wd`/`k`/`kw`/`kp`) and path styles (`/search/…`, `/s/…`), `+`/`%XX`-decoding best-effort. This catches Google/Bing/DDG image searches too (the `q=` is in the URL). Gated behind opt-in pref `contentFilterStrictKeywords` (default false) so the general userbase isn't hit by news-search false positives. **Firefox caveat (device-verified v1.8.0):** the block fires on the initial search navigation (URL has `q=<term>`), but once on the Google Images tab (`udm=2`) Firefox's URL-bar contentDescription DROPS the `q=` param, so a direct Images-tab load without `q` won't re-fire. URL-bar architecture cannot see inline images on a benign-URL page — only the URL. `DEFAULT_KEYWORDS` also expanded with coined/compound low-collision tokens (redgifs, porngif, stripchat, …) — never bare "gif".
- `EvaluateBlockUseCase.evaluateWebDomain()` — after the per-rule `webDomains` check finds no match, falls through to `evaluateContentFilter()`: if `contentFilterEnabled` and `ContentFilter.isBlocked(url)`, builds an `ActiveRule` with the configured `contentFilterMode` (tracking package `"web"`, ruleName "Restricted content") and runs it through `BlockEngine`. Reuses the existing overlay/passthrough path (HARD_BLOCK never sets passthrough, so it always re-blocks).
- Prefs: `NudgePreferences.contentFilterEnabled` (default **false**, opt-in) + `contentFilterMode` (default `"HARD_BLOCK"`) + `contentFilterStrictKeywords` (default **false**, gates the query-scoped ambiguous-slang matching). All DataStore — no Room migration.
- `ContentFilter.isBlocked(url, strictKeywords)` OR-chains `matchesDomain || matchesKeyword(DEFAULT_KEYWORDS)` plus, when `strictKeywords`, `matchesQueryKeyword(AMBIGUOUS_QUERY_KEYWORDS)`. `EvaluateBlockUseCase.evaluateContentFilter` reads the strict pref and threads it through.
- UI: "Content Filter" section in `SettingsScreen.kt` — "Block restricted websites" master switch + "Strict keyword matching" sub-toggle (both direct-`NudgePreferences`, no ViewModel).
- Tests: `ContentFilterMatcherTest` (domain/keyword/false-positive guards + the removed-token regression corpus, OFF and ON), `WebDomainDetectorTest` (multi-browser id resolution + mockk node reads), `EvaluateBlockContentFilterTest` (enabled/disabled/mode wiring, repo mocked — never loads the asset), and **`ContentFilterAssetTest`, which tests the SHIPPED ASSET itself** — the blob bug lived in the data, so tests over a hand-written fake blocklist could never have caught it. It reads the real file via `ContentFilterRepository.parseLine` and asserts: a ≤3,000-entry / <1MB ceiling (the durable guard against someone pasting a blob back in), lowercase registrable domains, no duplicates, **no `.gov`/`.edu`/`.mil`/`.int`/`.ac.*` entry ever**, no public-suffix entry (`co.uk` would block every British site), no collision with `ALLOWLIST`, no entry made redundant by a parent entry, and a **~65-domain benign corpus** (state government, universities, `.gov.au`, banks, news, CDNs/hosting, plus the policy-excluded sex-ed/fan-fiction/creator/retail sites) that must match via **neither** the domain nor the keyword layer — alongside positive assertions that the majors and 18 no-signal-token sites still block.

## Strict Mode (commitment lock) architecture — v1.7.0

Opt-in lock that gates every protection-WEAKENING action behind a typed unlock challenge. Strengthening is never gated. Two layers: in-app gate + OS escape-route guard.

- **Prefs** (`NudgePreferences`): `strictModeEnabled` (default false) + `strictModeChallengeLength` (default 24; Easy 12 / Medium 24 / Hard 48). Same `Flow` + setter pattern as `globalEnabled`.
- **`domain/lock/StrictModeChallenge.kt`** — pure Kotlin. `generate(length)` (unambiguous charset, excludes 0/O/1/l/I), `forDisplay` (dash-grouped chunks of 5), `normalize`/`rawLength` (dash- + whitespace-strip), `verify(input, target)` (case-sensitive, dash-insensitive **both** directions). The dialog counter and `verify` share `normalize`, so "x/y" can never disagree with what's compared.
- **`domain/lock/RuleWeakening.kt`** — pure `isWeakening(old, new)`: disable, mode softening (HARD_BLOCK>DELAY>BREATHING>none), shorter delay, and (v1.10.0) any auto-kick axis softened — `dailyLimitMinutes`, `autoKickAfter` and `autoKickAfterMinutes` raised-or-removed-when-set (one shared `isNullableAllowanceRaised` helper: all three are "allowances" that permit more usage the higher they are), plus `autoKickCooldownSeconds` **lowered** (note the inverted direction: a shorter cooldown lets you back in sooner).
- **`domain/lock/SettingsWeakening.kt`** (v1.10.0) — the same idea for the Settings screen's global switches. `requiresUnlock(toggle, enable, strictModeEnabled)` over `LockedToggle` = { `STRICT_MODE` (OFF weakens), `EMERGENCY_PASS` (ON weakens — it re-opens a one-tap bypass) }. Nothing is gated while Strict Mode is off. The escape-hatch toggle is deliberately **not** frozen under Strict Mode; see the Daily-2-minute-pass section.
- **`ui/lock/StrictModeGate.kt`** — ViewModel-side helper: `run(prompt, action)` runs immediately if Strict Mode off, else defers the action and emits a `ChallengeState` the screen renders. Used by `HomeViewModel` (global toggle ON→OFF only), `ActiveRulesViewModel` (rule disable), `UnifiedAppConfigViewModel` (weakening save / delete). `SettingsScreen` has no ViewModel, so it runs the same contract locally: one `PendingSettingsUnlock` slot (target + prompt + deferred action) feeding `ChallengeDialog`, with `SettingsWeakening.requiresUnlock` deciding which flips are gated — covering Strict-Mode-OFF **and** escape-hatch-ON.
- **`ui/components/ChallengeDialog.kt`** — the unlock UI. Paste/copy suppressed via a no-op `LocalTextToolbar`; `imeAction=Done` clears focus to dismiss the keyboard. Fresh target per open.
- **Escape-route guard** (the OS-bypass layer):
  - `domain/lock/StrictModeEscapeGuard.kt` — pure `shouldGuardSettingsScreen(foregroundPkg, windowText, appLabel, strictEnabled, withinGrace)`. Fails CLOSED (blank/empty/exception → no guard); biased to fewer false positives (requires a settings package AND the app label AND a strong escape signal).
  - **Detection signatures** (tuned against live AOSP Settings on the Pixel 3, Android 12; app label "Nudge - App Blocker"): a11y **detail** page (`com.android.settings/.SubSettings`) keys on the label + **"shortcut"** / **"use <label>"** — NOT the bare word "accessibility", because the a11y **list** page also shows our label (that was the false-positive to avoid). App Info page (`.applications.InstalledAppDetails`) keys on label + **"Force stop"** + **"Uninstall"**.
  - `service/StrictModeEscapeManager.kt` (`@Singleton`) — in-memory 60s grace window (modeled on `PassthroughManager`); while in grace the service short-circuits so a committed user can complete their toggle/uninstall.
  - `ui/lock/StrictModeGuardActivity.kt` — full-screen overlay reusing `ChallengeDialog`; unlock → `grantGrace()` + finish (back to Settings); cancel/back/dismiss → reliable `GLOBAL_ACTION_HOME` (HOME-intent fallback). Registered in manifest like `BlockOverlayActivity` (singleInstance, excludeFromRecents, empty taskAffinity).
  - `NudgeAccessibilityService` guards in `onAccessibilityEvent` before the `SYSTEM_PACKAGES` early-return; bounded node-text harvest (≤800 nodes); Strict Mode flags cached off-main so the hot path never blocks on DataStore. `accessibility_service_config.xml` gained `flagRetrieveInteractiveWindows`.
  - **OEM/locale caveat**: detection is best-effort, verified only on AOSP/English. Other settings packages are tolerated in `SETTINGS_PACKAGES` but unverified; an untuned OEM/locale screen simply isn't guarded (a miss, never a trap). Safety invariant: the lock can never hard-trap the user — cancel always goes home, the challenge is always solvable, Strict Mode off disables all guarding.
- **Tests**: `StrictModeChallengeTest` (charset/length/uniqueness, verify exact + dash/whitespace-insensitive both directions), `RuleWeakeningTest` (every axis both directions), `SettingsWeakeningTest` (both toggles, both directions, strict on/off), `StrictModeGateTest` (off=immediate, on=deferred-then-run/cancel, + the Settings-screen composition for the escape-hatch toggle), `StrictModeEscapeGuardTest` (guard/no-guard matrix + list-page-not-trapped, fail-closed, OEM pkg), `StrictModeEscapeManagerTest` (grace open/expire/clear/re-grant).

## Global master toggle gating — v1.9.2

The home-screen master switch (`globalEnabled`, `NudgePreferences`) must suppress **all** enforcement when off — a disabled Nudge behaves as if uninstalled. Previously only the async rule-evaluation coroutine checked `isGlobalEnabled`; the synchronous auto-kick **cooldown** block (and counter/auto-kick paths) ran *before* that check, so a cooled-down or over-limit app still kicked the user after they toggled Nudge off.

- **Cached flag**: `NudgeAccessibilityService.globalEnabledCached` (`@Volatile`, defaults **true** = fail toward enforcement), collected off-main in `onServiceConnected` via the same cached-flags pattern as Strict Mode, so the hot accessibility path reads it synchronously without blocking on DataStore.
- **Single synchronous gate**: in `onAccessibilityEvent`, after the Strict-Mode escape guard + `SYSTEM_PACKAGES` return and **before** the `when(eventType)` dispatch, `if (!globalEnabledCached) { hideAllOverlays(); return }`. Because every enforcement path (rule eval, auto-kick cooldown overlay, auto-kick via `InteractionHandler`, counter + time-remaining overlays, web-domain, content filter, in-app feature detection) is downstream of that dispatch, one gate covers them all.
- **Toggle-off teardown** (`onGlobalDisabled`, fired on the cached-flag's true→false transition): `InteractionTracker.clearAllCooldowns()` + `EmergencyPassManager.cancelAll()` + hide awareness overlays (on Main). The emergency-pass expiry kick is also gated on `isGlobalEnabled` inside the job.
- **Strict Mode is independent**: the escape guard for the Settings/App-Info screens stays active regardless of `globalEnabled` (it's a commitment lock, not app-blocking), and Strict Mode's gate on turning the master toggle OFF is unchanged.
- **Tested**: `InteractionTrackerCooldownTest.clearAllCooldowns…`; device-verified (Calculator blocked when ON, usable when OFF, re-blocked when ON).

## Transient-window handling (keyboard / paste-popup re-block) — v1.9.3

Fix for [#5](https://github.com/astraedus/nudge/issues/5): after completing a delay, `PassthroughManager` shields app X from re-blocking until a genuine app switch. The bug was that a soft keyboard **not** in the hardcoded list, or the `android` framework package (which hosts the paste / long-press popup toolbar + toasts), surfaced a *different* package on a window event; that reached `evaluateForegroundPackage → clearIfAppChanged`, which wiped X's passthrough, so tapping back into X re-triggered the delay. Gboard/Samsung keyboards were only shielded by being hardcoded in `SYSTEM_PACKAGES`; the reporter's **FUTO** keyboard was not, so it hit the bug.

- **`NudgeAccessibilityService.isTransientNonAppPackage(pkg, currentImePackage)`** (pure, `internal`, unit-tested) — true for the `FRAMEWORK_PACKAGE` (`"android"`), the static `IME_PACKAGES` fallback, or a **dynamic** match against `currentImePackage`. The active keyboard is read from `Settings.Secure.DEFAULT_INPUT_METHOD` (package half) and cached in `@Volatile currentImePackage`, kept fresh by a `ContentObserver` on that setting — so **every** keyboard is covered, not a hardcoded few.
- **`onAccessibilityEvent`**: after the own-package block, `if (isTransientNonAppPackage(pkg, currentImePackage)) return` — ignore the event entirely (no `clearOverlays`, no passthrough clear, no `lastPackage` move; the real app underneath hasn't changed). The 3 IME packages were **moved out** of `SYSTEM_PACKAGES` into `IME_PACKAGES` (they're now caught earlier by this return).
- **`isOverlayBypassedByForeground`** gained an optional `currentImePackage` param and now also excludes transients, so a keyboard/`android` window surfacing over a live block overlay isn't mistaken for the user re-entering the app.
- **Tests**: `TransientWindowTest` (FUTO-as-active-IME, hardcoded IME, `android`, real-app-never-transient, and the overlay-bypass regression guard). Device-verified on the Pixel 3: keyboard + paste-popup no longer re-block (logcat `skip evaluation … reason=passthrough` on return); a genuine app switch still re-blocks (logcat confirms `foreground evaluation` re-fires on real returns).

## Block overlay lifecycle — the delay only runs while you are looking at it (fixes #8)

Fix for [#8](https://github.com/astraedus/nudge/issues/8): the delay could be bypassed by tabbing out and back in. `BlockOverlayActivity` is `singleInstance` in its own task with an empty `taskAffinity`, so pressing Home mid-countdown only **stopped** it — the activity stayed alive in the background. The countdown ran as a plain `LaunchedEffect(Unit) { while (…) { delay(1000L); … } }`, which is **not** frame-gated (only recomposition pauses; `delay()` keeps running). The timer therefore hit zero invisibly while the user was on the launcher, called `onComplete()` → `passthroughManager.grant(pkg)` + `finish()`, and the next entry into the app hit `shouldSkipForegroundEvaluation` and opened with **zero** delay. The same stale background task is why the reporter saw the overlay/timer "persist" after tabbing out.

**Invariant: the delay only progresses while the overlay is actually on screen, and leaving the overlay abandons the attempt entirely.** Three layers, outermost first:

- **Finish on stop** — `BlockOverlayActivity.onStop()` clears `NudgeAccessibilityService.isOverlayActive` and `finish()`es, guarded by `!isFinishing` (the normal completion paths — `onTimerComplete` / `navigateHome` / emergency pass — already finished us) and `!isChangingConfigurations` (**a rotation must not dismiss a live block**). Home, a recents switch or screen-off therefore dismiss the overlay; the next entry into the blocked app is evaluated fresh and gets a fresh FULL delay. This also permanently removes the orphaned-overlay-task case that `isOverlayBypassedByForeground` exists to paper over.
- **Lifecycle-gated tickers** — both countdowns run inside `lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED)` (`DelayContent`, `BreathingContent`), covering the `onPause`→`onStop` gap. `repeatOnLifecycle` **cancels and restarts from the top**, so all countdown state is `remember`ed OUTSIDE the block and a pause resumes rather than restarts. Uses `androidx.lifecycle.compose.LocalLifecycleOwner` (NOT the deprecated `androidx.compose.ui.platform` one); `repeatOnLifecycle` resolves from the existing `lifecycle-runtime-compose:2.8.7`, which carries `lifecycle-runtime-ktx` as an **api** dependency — no new Gradle dep needed.
- **Defensive grant** — `onTimerComplete()` only calls `passthroughManager.grant()` when `lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)`. A countdown that somehow completed while backgrounded finishes without opening the app.
- **Exactly-once completion** — `onComplete()` sits after the countdown loop *inside* the `repeatOnLifecycle` block, so once the count has reached zero a pause/resume cycle would fall straight through the loop and fire it again. Both composables guard it with an `AtomicBoolean.compareAndSet`. Not reachable today (`onTimerComplete` always `finish()`es, so the activity never returns to RESUMED after completing) — but `onComplete` **grants passthrough**, and this path must not depend on an invariant that lives in another file.
- `BreathingContent` additionally accumulates elapsed time **per visible segment** (`advanceBreathingElapsed`/`breathingProgress`/`isBreathingComplete`, pure + unit-tested in `BreathingElapsedTest`) instead of measuring `now - startTime` from one timestamp — otherwise wall-clock time spent away from the overlay would still count toward completion. The accumulator clamps at zero so a backwards clock jump cannot rewind progress.
- **Known cosmetic**: rotation recreates the activity, and countdown state is `remember` (not `rememberSaveable`), so the timer restarts at full length. Errs toward *more* delay, never less.
- **Device-verified** (Pixel 3, 15s DELAY rule on Keep): Home mid-count → wait past the would-be expiry → return shows a fresh full delay with no passthrough grant in logcat; Home → immediate return shows 15/15, not a continued count; rotation mid-count keeps `BlockOverlayActivity` resumed.

## Content-change app-switch fallback (fixes #7)

Fix for [#7](https://github.com/astraedus/nudge/issues/7): "occasionally app timer does not start on re-entrance of app". Re-entering an app via the **recents overview** or a **notification tap** sometimes delivers only `TYPE_WINDOW_CONTENT_CHANGED`, with no `TYPE_WINDOW_STATE_CHANGED`. `handleWindowContentChanged()` only routed to `evaluateForegroundPackage()` for browsers and for `InAppDetector.SUPPORTED_PACKAGES`; for every other app such a re-entry produced **no evaluation at all** — no delay re-block, no counter session, no time-remaining overlay. This is the same defect previously logged in the backlog as "genuine app-switch may not re-block when the interaction counter overlay is active".

Edge-triggering on *every* content change would fix it and immediately reintroduce [#5](https://github.com/astraedus/nudge/issues/5) — content-change events also arrive from windows that are not in front, so a ghost app-switch would wipe post-delay passthrough and re-block the user. The fallback is therefore **state-verified**:

- **`NudgeAccessibilityService.shouldTreatContentChangeAsAppSwitch(packageName, lastPackage, ownPackageName, currentImePackage, activeWindowPackage)`** (pure, `internal`, unit-tested) — requires the event's package to also own the **real active window** (`rootInActiveWindow`), and rejects our own package, `SYSTEM_PACKAGES`, `FRAMEWORK_PACKAGE` and any IME (the active one matched dynamically) first. A null/unreadable active window is **never** a switch: unverifiable means do nothing, because a false positive costs the user their passthrough while a false negative just retries on the next event.
- `activeWindowPackage` is a **lambda**, invoked only after the cheap comparisons, so the node-tree read never runs for the app the user is already in. Additionally throttled per package (`SWITCH_CHECK_DEBOUNCE_MS = 500`) because `evaluateForegroundPackage` early-returns for an active emergency pass or live passthrough **without advancing `lastPackage`** — without the throttle the read would repeat on every content change for the whole of that window.
- The transient-window early-return in `onAccessibilityEvent` stays **upstream** of this path, and passthrough is still only ever cleared by `clearIfAppChanged` on a genuinely different foreground app.
- **`TYPE_WINDOWS_CHANGED` with a null package** (dropped at `event.packageName ?: return`) was investigated and deliberately **not** handled: on the Pixel 3 every recents re-entry was caught by the content-change path, so a null-package handler would add active-window reads with no behavioural gain.
- **Tests**: `ContentChangeAppSwitchTest` — verified re-entry evaluates; same package / unverified package / null active window / IME (incl. the FUTO case from #5) / framework / system / own package never do; plus a cost test asserting the active window is not read for cheaply-rejected events.
- **Device-verified with an explicit counterfactual** (Pixel 3, 15s DELAY rule on Keep, alternating Contacts ↔ Keep through the recents overview): on the pre-fix build **5 of 6 re-entries produced no `foreground evaluation` and no block** — the bug reproduced on demand; with the fix, **6 of 6** re-entries evaluated and blocked, every one of them routed through the new fallback. The #5 regression case (complete the delay, raise the keyboard with `mInputShown=true`, keep using the app) logs `ignoring transient non-app window …inputmethod.latin` and `skip evaluation … reason=passthrough` with **zero** re-blocks.

## Picture-in-picture escape — detect, explain, deep-link (fixes #19)

Fix for [#19](https://github.com/astraedus/nudge/issues/19), found by @polubarev during PR #17 QA: when the block overlay backgrounds YouTube, YouTube enters **picture-in-picture** and the Short keeps playing. `BlockOverlayActivity` is correctly fullscreen and `topResumedActivity` and **still loses** — a PiP window is always-on-top by design. This is platform behaviour, not an overlay bug, and there is **no public API** for one app to disable PiP for another. The only real remedy is the per-app PiP permission in Settings, which only the user can flip.

**The honest shape is therefore detect-and-deep-link, not detect-and-fight.** Nudge does not try to kill the PiP window.

- **`service/PipWindowProbe.kt`** — `packageInPictureInPicture()` answers "what is in PiP right now?" from `AccessibilityService.getWindows()` (works because `accessibility_service_config.xml` already carries `flagRetrieveInteractiveWindows` for Strict Mode). Two cost decisions: the service's reader resolves a window's **owner only for windows already flagged PiP** (`AccessibilityWindowInfo.getRoot()` is a binder read *per window*, and a device has many windows but at most one in PiP — non-PiP windows come back with a null package), and the answer is cached for `DEFAULT_THROTTLE_MS = 500` (same cadence as the issue #7 active-window check). Caching is safe both ways: a stale `true` keeps the block asserted slightly longer (fail-safe), a stale `false` is caught on the next event. `pipPackage(windows)` is a pure companion function, so the selection rule is unit-tested without an Android window list. **A PiP window whose owner won't resolve is skipped, never guessed** — the result is compared against the blocked package, and a wrong match would suppress a genuine re-block.
- **`NudgeAccessibilityService.isPipEscapeOfActiveBlock(eventType, packageName, blockedPackage, pipPackage)`** (pure, `internal`, unit-tested) — same shape as its prior-art siblings: cheap rejections (not a window-change event / no live block / event package ≠ blocked package) run **before** the lazy `pipPackage` lambda, so the overwhelmingly common event on this branch — our own overlay's window churn — costs nothing.
- **`blockedPackage` + `isOverlayActive` are now paired** behind `markOverlayActive(pkg)` / `markOverlayInactive()` (both `private set`). The two facts must never disagree and there are six launch/dismissal sites across the service, `BlockOverlayActivity` and (indirectly) `TimeRemainingHandler`. `BlockOverlayActivity` marks inside `render()` rather than `onCreate`/`onNewIntent`, because those run **before** the intent is parsed and the PiP check needs the package, not just "something is up" — `render()` is called synchronously from both, so there is no unset window.
- **Honest stats — the part that was quietly corrupting data.** A PiP window fires window events carrying the blocked app's package, and `isOverlayBypassedByForeground` reads those as "the user came back". Left alone it cleared the overlay flag, re-evaluated, and logged a fresh `UsageEvent(wasBlocked = true)` on **every** such event — the blocked count climbed on its own while a Short auto-played. The PiP check runs **first** and claims those events: block stays asserted, nothing re-evaluated, nothing logged. `PipEscapeActivity` likewise writes no `UsageEvent` and grants no passthrough (a platform escape is neither a block the user hit nor a walk-away), and while `PipEscapeActivity.isActive` the service swallows events entirely — the explainer *stands in for* the block overlay, so re-evaluating behind it would relaunch the overlay on top of it and double-log.
- **`ui/overlay/PipEscapeActivity.kt` + `PipEscapeContent.kt`** — full-screen explainer registered like `StrictModeGuardActivity` (singleInstance, excludeFromRecents, empty taskAffinity), with the same `onStop → finish` discipline so no orphaned task lingers. Back / "Not now" just closes: unlike the Strict Mode guard it does **not** force the user home — the block overlay it replaced is already gone and there is nothing left to protect.
- **The deep link (device-probed, do not re-derive).** `Settings.ACTION_PICTURE_IN_PICTURE_SETTINGS` is **NOT a public SDK constant** (it is `@hide` in AOSP) — referencing it will not compile. `ui/overlay/PipSettingsTarget.kt` holds the raw action string and an ordered candidate list, resolved at runtime via `resolveActivity` (the manifest's `QUERY_ALL_PACKAGES` makes this work on API 30+). Verified on the Pixel 3 / Android 12 with `cmd package query-activities`: action + `Uri.fromParts("package", pkg, null)` → `Settings$AppPictureInPictureSettingsActivity` (**the per-app toggle — the one that actually fixes it**); action alone → `Settings$PictureInPictureSettingsActivity` (the list). Both `exported=true`. Falls back to `ACTION_APPLICATION_DETAILS_SETTINGS`, then to on-screen manual instructions — an unresolvable intent throws `ActivityNotFoundException` in the user's face, and the deep link is this screen's whole value.
- **Prompt-once**, via `domain/pip/PipEscapeLedger.kt` (pure; `;`-separated set, `MAX_ENTRIES = 200`, oldest dropped first, idempotent `record`) persisted as `NudgePreferences.pipEscapePromptedPackages` and cached off-main in the service like the other hot-path flags. This is one-shot **education about a platform limitation**, not an enforcement surface — repeating it would be pure nagging. `parse` fails soft to "nothing prompted yet": failing hard would crash the hot path, and failing soft the other way would silently kill the feature. The service marks its in-memory cache **before** the DataStore write so an event burst cannot stack explainers.
- **Tests**: `PipEscapeTest` (14 — escape recognised on both window-change event types; genuine foreground return, another app's PiP, a different app, our own package and content-change churn all rejected, the last three asserting the window list is **not read**; `pipPackage` selection incl. unresolved owners; the throttle's read-count and its first-question-at-clock-zero sentinel), `PipEscapeLedgerTest` (7), `PipSettingsTargetTest` (5, incl. the exact action-string literals).
- **Device QA still owed** — see the cases in the PR/handoff. Structurally the fix cannot be fully proven on the JVM: whether `isInPictureInPictureMode` is set for YouTube's PiP on a real device, and whether the per-app deep link lands on the toggle, are on-device facts.

## Daily 2-minute pass (emergency escape hatch) — v1.9.0, made GLOBAL + 2min in v1.9.2

Opt-in escape hatch on the block overlays. **ONE 2-minute free window per rolling 24h across the WHOLE device** (v1.9.2 — was per-app, 1 minute). Using it on any app grants that app a 2-minute window AND locks the pass out for *every* app for 24h. Availability is governed **solely by its own Settings master toggle** — Strict Mode does NOT hide it (changed in v1.10.0; see "Strict Mode vs. the escape hatch" below).

- **`domain/emergency/EmergencyPass.kt`** — pure Kotlin. Ledger `parse`/`serialize` (format `pkg=epochMillis;…`, fails soft to empty map on malformed input, never throws) kept for **migration**: `globalLastUsed(usage)` takes the MAX timestamp across ALL entries, so a legacy per-app ledger is reinterpreted as one global last-used. `canUseGlobal(usage, now, cooldownMs)`, `nextAvailableGlobalMs(...)` (remaining lockout for the UI hint), `recordGlobal(now)` (collapses the ledger to a single `GLOBAL_KEY="*"` entry). Constants `PASS_DURATION_MS=120_000`, `LOCKOUT_MS=86_400_000`. Fully unit-tested (`EmergencyPassTest`, 22 cases incl. cross-app lockout + migration).
- **`service/EmergencyPassManager.kt`** (`@Singleton`) — modeled on `PassthroughManager`. In-memory `activeUntil: ConcurrentHashMap<pkg, Long>` — the active window stays **per-app** (pressing the pass on Instagram unblocks Instagram, not everything); only the lockout is global. Per-package `kickJobs` so a fresh grant replaces the prior timer. `isPassActive(pkg)` is the non-blocking hot-path check. `usePass(pkg)` opens the window, persists the global lockout (`prefs.recordEmergencyPassUsed(now)`), and schedules `delay(PASS_DURATION_MS) → remove → kickHome()` **only if `isGlobalEnabled`** (a scheduled kick must not fire while Nudge is globally disabled). `cancelAll()` cancels every window + pending kick (called on global-disable). `kickHome()` prefers `NudgeAccessibilityService.requestGoHome()` and falls back to a HOME intent. The active window is in-memory only — a restart ends the window (fail-safe toward re-blocking); only the lockout is persisted.
- **Prefs** (`NudgePreferences`): `emergencyPassEnabled` (bool, default **true**) + `emergencyPassUsage` (serialized ledger string) + `recordEmergencyPassUsed(now)` (overwrites the ledger with the single global entry — the lockout is global, no per-app merge).
- **Service integration** (`NudgeAccessibilityService`): `emergencyPassManager()` on the EntryPoint; in `evaluateForegroundPackage`, `if (isPassActive(pkg)) return` placed **before** the auto-kick-cooldown block so an active pass overrides cooldown too. When the window expires the scheduled kick sends the user home AND the next foreground event re-blocks normally (backstop).
- **UI**: `ui/overlay/EmergencyPassAction.kt` — the pure resolver **plus** one shared composable rendered by all three overlays below the primary button: muted `TextButton` "Use for 2 minutes · once a day" when available; a **disabled/greyed** `TextButton` "Daily pass used · next in Xh" when spent (visible, not hidden); nothing otherwise. `internal fun resolveEmergencyPassState(packageName, passEnabled, usage, now, lockoutMs)` → `EmergencyPassUiState(canUse, locked, nextPassMs)` owns the **entire** decision (pseudo-package skip → toggle → `canUseGlobal`/`nextAvailableGlobalMs`); it is pure so it is JVM-tested (`EmergencyPassVisibilityTest`) instead of buried in the Activity. `BlockOverlayActivity` just calls it inside the existing `runBlocking` alongside the message pools (correct on first composition, no flash) and forwards the three fields. Rendered on ALL `BlockOverlayActivity` launch paths — rule blocks (`handleDecision`), auto-kick cooldown DELAY (`evaluateForegroundPackage`), and daily-limit HARD_BLOCK (`TimeRemainingHandler`) — since all set a real package and go through `render()`. Tap → `emergencyPassManager.usePass(pkg); finish()`. Settings master toggle under "Escape Hatch" (live under Strict Mode; enabling it is challenge-gated).
- **Strict Mode vs. the escape hatch (v1.10.0)** — Strict Mode used to hide the pass on overlays and freeze/grey the Settings toggle. That silently revoked an escape hatch the user had deliberately opted into, so the lock now bites where protection is actually WEAKENED instead: **`resolveEmergencyPassState` takes no strict-mode flag at all** (a regression would have to add the parameter back), and turning the toggle OFF→ON while Strict Mode is on requires the typed unlock challenge. Turning it ON→OFF strengthens protection and is always free. Policy lives in `domain/lock/SettingsWeakening.requiresUnlock(toggle, enable, strictModeEnabled)` with `LockedToggle` = { STRICT_MODE, EMERGENCY_PASS } — the Settings-screen sibling of `RuleWeakening`.
- **Device-verified**: v1.9.0 (per-app 1min); v1.9.2 (global 2min button text, cross-app greyed lockout). v1.10.0 semantics change is unit-tested; device QA pending.

## Export/Import architecture

- `data/export/RuleExportData.kt` — data classes: `NudgeExport`, `ExportedRule`, `ExportedGroup`
- `data/export/RuleExporter.kt` — serialization/deserialization via `org.json` (no extra dependency). Handles null fields, version validation, block mode validation. `@Singleton` with `@Inject`.
- `domain/usecase/ExportRulesUseCase.kt` — collects enabled rules + groups + members, delegates to RuleExporter
- `domain/usecase/ImportRulesUseCase.kt` — `preview()` returns count, `execute()` inserts with duplicate detection (packageName + mode + schedule match). Creates groups by name if missing.
- UI: three-dot overflow menu in `ActiveRulesScreen` with "Export Rules" (share intent via FileProvider) and "Import Rules" (ACTION_OPEN_DOCUMENT file picker). Confirmation dialog before importing.
- Export format: pretty-printed JSON, version 1, human-readable. Groups referenced by name (not ID).

### Import fails per-ENTRY, never per-FILE (fixes #20)

Fix for [#20](https://github.com/astraedus/nudge/issues/20). `importRules` mapped eagerly over the rules array, so the first unparseable entry threw out of the loop and the catch returned an empty list: **one bad rule silently discarded every rule AND every group in the file** — on the app's only backup path. The `BlockMode.NONE`-missing-from-the-whitelist trigger was fixed earlier (whitelist now derived from `BlockMode.entries`); this fixes the failure *shape*, which is what generalizes to the next unexpected field.

- **`parseEach(array, label, skipped, parse)`** parses each element in its own `try`, appending `"Rule 3: <reason>"` to a shared `skipped` list instead of aborting. Rules and groups both go through it, so the two can never drift in how tolerant they are. It catches exactly what the envelope handler catches (`JSONException`, `IllegalArgumentException`) — the two ways a malformed entry can fail.
- **`ImportResult.invalidCount` / `.invalidReasons`** carry the skips (the reason strings are capped at 20 — they are display material driven by file content, so `invalidCount` is the authoritative total, never `invalidReasons.size`); `ImportOutcome` passes them to the UI and renames `skippedCount` → **`duplicateCount`**. The two are deliberately separate: a duplicate was understood and intentionally not re-added, an invalid entry is something the user LOST from their backup. Merging them into one "skipped" number would hide the data loss the issue is about.
- **The envelope still fails loudly.** Not JSON, bad version, a `rules`/`groups` key that is present but not an array (`arrayOrEmpty` throws — absent/null is still legitimately "none"), and — the subtle one — **a file where nothing at all was importable**: skipping every entry returns an `error`, never a cheerful "Imported: 0". An empty-but-well-formed backup (a user with no rules) is still a success.
- **Validation added**: a group with a blank name is invalid (it would otherwise create an unnamed group).
- **UI**: `ui/screens/rules/ImportMessages.kt` — `buildImportPreviewMessage` / `buildImportOutcomeMessage` are pure so the disclosure wording is JVM-tested, not eyeballed on a device. The preview warns *before* writing ("2 entries could not be read and will be left out:" + up to 3 reasons); the result dialog reports "Skipped (could not be read): N" separately from duplicates. Extracting them also fixed a string-concat precedence bug (`… else "" + "?\n\n…"`) that dropped the trailing sentence from the preview whenever the file contained groups.
- **Tests**: `ImportSkipInvalidTest` (16 — mixed file, missing/wrong-typed fields, non-object elements, unknown schema junk tolerated, invalid groups, all-invalid, reason capping, envelope shapes), `ImportRulesUseCaseTest` (6 — real `RuleExporter` + mockk repo, asserts what actually reaches the DB and that the three counts are independent), `ImportMessagesTest` (6 — the wording). 13 of these fail against the pre-fix eager parse (verified by reverting the parse loop and re-running).

## Stats visualization architecture

- `ui/screens/stats/StatsCalculator.kt` — pure Kotlin (no Android deps), injected via Hilt. Methods: `buildWeeklyDataFromTotals`, `buildTrendData`, `buildAppTrendData`, `calculateStreak`. Fully unit-testable. The event-summing `buildWeeklyData`/`buildHourlyData` pair was deleted together with `durationMs` (#22) — screen-time series come from `ScreenTimeProvider` (UsageStatsManager) and reach the charts as pre-computed totals; `usage_events` only feeds the blocked/walked-away counts and the streak.
- `ui/screens/stats/charts/WeeklyBarChart.kt` — Canvas-based 7-day bar chart with rounded corners, day labels
- `ui/screens/stats/charts/BlockedTrendChart.kt` — dual chart: bars (blocked) + line with dots (walked away)
- `ui/screens/stats/charts/HourlyHeatmap.kt` — 24-cell row, color intensity from surfaceVariant to primary
- `ui/screens/stats/charts/StreakCounter.kt` — flame icon + streak count + "X days streak" label
- All charts use Material 3 colorScheme exclusively, handle empty states, no external dependencies.

## Google Play compliance

### AccessibilityService prominent disclosure (required by Google Play policy)
- `ui/components/AccessibilityDisclosureDialog.kt` — Material 3 AlertDialog shown BEFORE requesting Accessibility Service permission
- Two buttons: "I Understand" (accept) / "Not Now" (decline). Back/tap-outside = decline, NOT consent.
- Explains: WHY (detect foreground apps), WHAT data (package names only), HOW used (locally, never sent)
- Wired into: `OnboardingScreen.kt` (onboarding flow) and `SettingsScreen.kt` (settings page)
- Demo video (unlisted): https://youtube.com/shorts/0ZN77tEcFzQ — linked in Play Console Accessibility Services declaration
- If Google rejects again: check the specific reason. The dialog text, button labels, and dismiss behavior all matter. See https://support.google.com/googleplay/android-developer/answer/10964491 for full requirements.

## Store listing

Assets at `store-listing/` — feature graphic, screenshots, listing copy, batch config (`screenshots.json`).

## Testing Philosophy — Never Regress

**Every new feature MUST ship with tests that cover its core behavior.** The test suite is the safety net that lets us move fast without breaking existing functionality.

Principles:
- **Tests are not optional.** If you add a feature, you add tests. No exceptions.
- **Test the contract, not the implementation.** Domain logic (BlockEngine, use cases, StatsCalculator) gets unit tests. UI gets integration tests for navigation/state.
- **Run `./gradlew test` before every commit.** If tests fail, the feature isn't done.
- **Regression = bug.** If a new feature breaks existing behavior, that's a blocker — fix it before merging.
- **Domain layer is the priority.** Pure Kotlin with no Android deps = fast JVM tests. Test BlockEngine decisions, schedule evaluation, counter logic, export/import round-trips.
- **When fixing a bug, write a test that reproduces it first.** Then fix. The test proves the fix works and prevents re-introduction.

Test locations:
- `app/src/test/` — JVM unit tests (domain, data, use cases)
- `app/src/androidTest/` — instrumented tests (Room migrations, accessibility service behavior)

Coverage targets (aspirational, enforce on new code):
- Domain layer: >90% line coverage
- Data layer (repositories, DAOs): >70%
- UI ViewModels: key state transitions tested

## Post-feature checklist

After any feature addition or significant change:
1. Write tests covering the new behavior (unit + integration as appropriate)
2. Run `./gradlew test` and verify ALL tests pass (not just new ones)
3. Build debug APK: `./gradlew assembleDebug`
4. Install on Pixel 3: `adb -s 192.168.1.68:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
5. **QA on device** — spawn `device-tester` agent with specific test cases. PASS required before push.
6. If QA passes: bump `versionCode` + `versionName` (patch) in `app/build.gradle.kts`
7. Update CHANGELOG.md with version + date + changes
8. Update this CLAUDE.md (architecture docs, feature descriptions) if applicable
9. Commit all changes, tag, push: `git push origin main --tags`
10. Create GitHub release (fast path): `gh release create vX.Y.Z nudge-vX.Y.Z.apk --title "vX.Y.Z" --generate-notes`
11. **Publish to Google Play** — the standing default so Play never drifts behind GitHub again. After CI attaches the AAB, run `scripts/publish-to-play.sh X.Y.Z` — that ships to **100% of production** in one step, no follow-up owed. Stage (`STATUS=inProgress ROLLOUT=0.2 …`) only for a genuinely risky release, and file the promote step as a dated task if you do. Play credentials stay on the laptop — never in CI. See the **Releasing → Google Play** section for the security rationale.
12. Update store listing copy if user-facing

**This is the standard ship flow. Every change that touches user-facing behavior gets a device QA gate before push.**

**SHIP AUTONOMOUSLY — do NOT ask for permission once the device-QA gate passes.** This is a documented, reversible, owned release flow (own-the-last-mile rule). When QA is green: bump the version, update CHANGELOG/docs, commit, build `assembleRelease`, tag, `git push origin main --tags`, and `gh release create` — end to end, no confirmation step. Asking "should I push?" on a verified change is the exact anti-pattern this repo's flow exists to prevent. The ONLY things that still warrant a pause are the universal ones: money, real-world identity, known-contact email, ban-risk platform actions, or destructive/irreversible deletion — none of which a Nudge release involves.

## Backlog

### Known issues — surfaced incidentally during v1.9.0 device QA (pre-existing, NOT from the 1-min-pass feature; each needs its own investigation)
- [x] ~~**Genuine app-switch may not re-block when the interaction counter overlay is active**~~ (surfaced 2026-07-27 during #5 QA) — **RESOLVED**: this was the same defect users reported as [#7](https://github.com/astraedus/nudge/issues/7). The counter overlay was a red herring; the real cause is that a re-entry delivering only `TYPE_WINDOW_CONTENT_CHANGED` never reached `evaluateForegroundPackage` for non-`SUPPORTED_PACKAGES`. Confirmed with real recents-overview taps (not `monkey`): 5 of 6 re-entries missed on the pre-fix build. See "Content-change app-switch fallback". Likely related to the existing "counter doesn't increment on YouTube swipes under a whole-app rule" item below.
- [ ] **Interaction counter doesn't increment on YouTube swipes under a whole-app rule** (v1.9.2 QA, pre-existing) — the QA walk could not drive YouTube into auto-kick because plain feed swipes never counted (Shorts routes through the feature-rule path, bypassing the whole-app counter). Blocked the on-device repro of the auto-kick-cooldown QA case; the global-toggle gate covering that path is structurally verified (cooldown enforcement is downstream of the synchronous `globalEnabledCached` gate) but a live cooldown repro is still owed. Investigate what interaction types the counter registers per app/rule shape. **Sharpened by v1.10.0 QA (2026-07-27)**: per-session count never moved across 10+ real taps/swipes (Shorts AND home feed), yet a daily total incremented once per app-open, and repeated force-stop+reopen cycles eventually fired the kick — so on YouTube "after N interactions" effectively behaves as "after N app-opens", contradicting the label. Suspect only the open/window event reaches `InteractionTracker` for this app shape. (Single QA run; needs confirmation.) The NEW time-based trigger is unaffected — device-verified firing on fully passive playback.
- [ ] **Block overlay briefly re-renders after tapping the daily pass** (v1.9.2 QA, cosmetic, pre-existing — identical under the old per-app design) — grant/lockout/expiry all correct; the overlay flashes once before the app becomes usable. Likely the next foreground event re-launching the overlay before `isPassActive` is consulted. Cosmetic polish only.
- [ ] **Accessibility service instability under sustained load** — during a ~90min automated torture test (many app launches, force-stops, PIP overlays) the a11y service churned/reconnected repeatedly and was found OS-unregistered once. Could NOT be reproduced in a normal cycle (process held 63min uptime, single PID, zero idle reconnects), so likely OS memory-pressure kill on the 3GB Pixel 3 rather than a code defect — but worth a foreground-service/`onUnbind` hardening pass + a repro on a clean device. Root-cause before assuming it's environmental.
- [ ] **Browsers bypass whole-app block rules** — a DELAY/HARD_BLOCK rule on Chrome never fires via the whole-app pipeline because browser packages route straight to per-URL web-domain evaluation. This is *by design* per the web-domain architecture (whole-app blocking a browser would nuke all browsing), but the UX is surprising — a rule silently does nothing unless "Block on web too" + a domain rule exist. Consider surfacing this in the rule editor when the target is a known browser.
- [ ] **System permission dialog can render over `BlockOverlayActivity`** — e.g. Camera's location-permission prompt kept re-appearing on top of the block overlay, leaving the blocked app's UI visible underneath even though the decision was correctly `HARD_BLOCK`. Overlay z-order / re-assert on `TYPE_WINDOW_STATE_CHANGED` for the permission-controller package.
- [ ] **"I changed my mind" can leave the user inside the blocked app** — reproduced on Calculator (post-lockout) and suspected on Chrome; `navigateHome()` should reliably land home, investigate the cases where it doesn't.
- [ ] **Content Filter over-promises vs. what a URL-bar architecture can deliver** (surfaced 2026-07-07, Anti tested it and it failed on Google Images). The accessibility URL-bar filter can catch known porn *domains* + keyword-in-URL navigations, but it structurally CANNOT block image-search results: (a) it only sees the URL, never the images on the page; the explicit thumbnails come from Google's own CDN (encrypted-tbn*.gstatic.com) which can't be blocked without blocking Google; (b) Firefox drops `q=` from the URL bar on the Images tab (`udm=2`), so the query is invisible; (c) keyword tuning is whack-a-mole and can't cover arbitrary explicit phrasings. The ONLY robust mechanism is DNS-level SafeSearch enforcement — **device-wide Private DNS → `family-filter-dns.cleanbrowsing.org`** (CleanBrowsing Family) or `family.adguard-dns.com` (AdGuard Family), both force-lock Google/Bing/YouTube SafeSearch AND NXDOMAIN porn domains, survive incognito, work in every browser. **Device-verified on the Pixel 3 2026-07-07** (pornhub.com → ERR_NAME_NOT_RESOLVED; Google Images "porn" → "SafeSearch is locked by your network or device", no explicit thumbnails). Cheap honest fix so users don't hit the wall and 1-star it: in the Content Filter settings, add a one-tap "Block adult content device-wide" that deep-links to Private DNS + pre-fills the hostname (`Settings.ACTION_PRIVATE_DNS_SETTINGS` where available), framed honestly as device-level; keep the in-app URL-bar filter as a light domain/keyword catch, don't imply it does image search. Strict Mode could additionally guard the Private DNS settings screen from being toggled off (same escape-route-guard pattern already used for the a11y settings page). Decision on whether to build this (wizard vs. bundled VpnService filter vs. leave as-is) left to Anti — he leaned "that's as fair as Nudge can go" / handle it via DNS himself.

### v1.2 in progress
- [x] Time remaining overlay (code-complete, verified on device)
- [x] Auto-kick cooldown (code-complete, verified on device)
- [x] Rule name on block overlays (code-complete, verified on device)
- [x] Export/Import rules (code-complete, tests pass, needs on-device QA)
- [x] Enhanced stats visualizations (code-complete, tests pass, needs on-device QA)
- [x] Dynamic version display from BuildConfig
- [x] Tag-triggered GitHub Actions release pipeline (`.github/workflows/release.yml`)
- [ ] Instagram home feed detection -- code written but AccessibilityService API doesn't expose child node `selected` state through `findAccessibilityNodeInfosByText/ViewId`. Needs tree-walk approach: traverse from `rootInActiveWindow`, find ImageView nodes with `selected=true` in bottom nav, match to parent tab. See InAppDetector.kt.
- [ ] On-device QA for all v1.2 features
- [ ] YouTube Shorts verification on device

### v1.3+
- [ ] **QR code unlock** -- physical friction for bypassing blocks. User generates a QR code in settings (random secret encoded via ZXing), prints/places it somewhere inconvenient. Per-rule toggle `requireQrUnlock`. Block overlay gets "Scan QR to unlock" button that opens camera (ML Kit barcode scanner), verifies against stored secret, grants passthrough. Adds camera permission (only one we'd need beyond accessibility). Twist: multiple QR codes with different unlock durations (e.g. "bedroom QR" = 10min, "office QR" = 1hr). Could also give QR to a friend for accountability. Low implementation complexity, high user-perceived value.
- [ ] **Advanced data visualization** -- expand beyond current charts: per-app weekly breakdown, comparison vs previous week, export charts as image for sharing/accountability.
- [ ] Discord in-app detection: count server/channel switches as "taps" for counter + auto-kick. Discord uses React Native so TYPE_VIEW_CLICKED doesn't fire. Would need to detect server/channel navigation via accessibility tree changes. Low priority.
- [ ] NFC tag unlock -- same concept as QR but tap phone to NFC tag. No extra permissions needed (hardware feature). User writes unlock token to a cheap NFC tag ($1), places it somewhere. Lower priority than QR since fewer people have NFC tags lying around.
- [ ] Widgets (home screen quick stats, toggle rules)
- [ ] Contextual triggers (location-based, time-of-day auto-enable)
- [x] Release signing key (v1.3.2 -- PKCS12 keystore, CI via GitHub secrets)
