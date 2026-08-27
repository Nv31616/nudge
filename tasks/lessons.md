# Nudge -- Project Lessons

## Stats display: "today only" looks like data loss (2026-05-20)

HomeScreen stats (Blocked, Walked Away) were filtered to today only. Users updating the app (often at start of day) saw 0 stats and thought the update wiped their data. No actual data loss -- all Room migrations are additive ALTER TABLE, no `fallbackToDestructiveMigration()` or `clearAllTables()` anywhere.

Fix: Added "All Time" stats alongside "Today" on the home screen. When adding new stats, always consider whether the user needs both a time-scoped view and a cumulative view.

## cleanup() is dead code (2026-05-20)

`UsageRepository.cleanup()` exists but is never called anywhere. No scheduled cleanup, no startup cleanup. If we add cleanup later, verify the retention window doesn't surprise users (30 days default).

## Migration test must track currentVersion (2026-05-20)

`NudgeDatabaseMigrationTest` was stuck at `currentVersion = 6` while the DB was at version 7. When adding a new migration, always update the test's `allMigrations` list AND `currentVersion`.

## AccessibilityNodeInfo needs mockk in JVM tests (2026-06-16)

`WebDomainDetectorTest` originally only tested `isBrowser`/null cases because reading `AccessibilityNodeInfo` (Android framework class) throws "not mocked" in plain JVM tests. To test `detectUrl`'s node reads, add `testImplementation("io.mockk:mockk:1.13.13")` and `mockk<AccessibilityNodeInfo>(relaxed = true)`. Better still: extract the pure logic (e.g. `urlBarViewIdsFor()` id resolution) so most coverage needs no Android mocking at all.

## Play draft→full: don't run publish-to-play.sh twice (2026-07-19)

`scripts/publish-to-play.sh <ver>` UPLOADS the AAB on every run. The documented two-step (draft to verify, then `STATUS=completed ROLLOUT=1.0 …` for full rollout) fails on the second run with `Error 403: Version code N has already been used` — the draft already consumed that versionCode. To promote an already-staged draft to a completed full rollout WITHOUT re-uploading: `gplay edits create` → `gplay tracks update --edit <id> --track production --releases '[{"status":"completed","versionCodes":["N"],"releaseNotes":[…]}]'` → `gplay edits validate` → `gplay edits commit`. (The single completed release supersedes the prior one automatically.) Better: for a confident release, skip the draft step and run the full-rollout invocation once. NB: the script truncates CHANGELOG release notes at ~500 chars mid-word — for a clean Play "What's new", pass hand-written notes in the `tracks update` releases JSON.

## Content filter framing is a hard constraint (2026-06-16)

The web content filter blocks adult sites but MUST stay generically framed everywhere user-visible: setting title "Block restricted websites", overlay rule name "Restricted content". Blocklist (`assets/content_filter_domains.txt`) + `DEFAULT_KEYWORDS` live only in code/assets. When grepping for accidental leaks, note `hasExisting`/`hasExceeded` are false-positive substring hits for "sex"/"xxx", exactly the ambiguous-token class the keyword list avoids.

## A transient GitHub API 5xx must not fail the Release run (2026-07-20)

Release run 29710052363 failed on a docs-only commit: the "Publish rolling dev build (main)" step of `.github/workflows/release.yml` hit `error checking for existing release: HTTP 503` from `gh release create`. The build, tests, AAB/APK, and artifact upload had ALL succeeded -- only the convenience `main-latest` publish flaked, and it had no retry, so one momentary GitHub API blip sank the whole run.

Fix: the rolling-tag publish is now an idempotent `publish_rolling_build()` (delete-then-create) retried with exponential backoff (5 attempts). Lesson for any CI step that calls a flaky external API: wrap network mutations in a retry-with-backoff; make them idempotent (a failed delete is re-attempted before create); and let a *persistent* failure still exit non-zero so real breakage stays loud. Do NOT reach for `continue-on-error` -- that would swallow genuine failures too.

## The content-filter blocklist was a 274k upstream blob and it over-blocked badly (2026-08-19)

Anti reported the filter as "way too punishing, blocks Reddit sometimes and even government websites". Both halves were data bugs, not matcher bugs, and every unit test was green throughout because they ran against a hand-written 2-entry fake blocklist.

`assets/content_filter_domains.txt` was a ~274,642-domain (4.5MB) third-party blob. Still in it at v1.10.0: `virginia.gov`, `purdue.edu`, `rice.edu`, `ku.edu`, `metrostate.edu`, `ohiochristian.edu`, `itu.int`, `utwente.nl`. Worse, `matchesDomain` walks parent domains, so its entries for `amazonaws.com`, `cloudfront.net`, `wordpress.com`, `blogspot.com`, `myshopify.com` and `appspot.com` silently blocked **every site hosted on those platforms**. The v1.9.2 `reddit.com` ALLOWLIST guard was the same defect surfacing once and being treated as a one-off; a one-site allowlist patch for a bad-data problem always is.

`DEFAULT_KEYWORDS` separately raw-substring-matched ordinary English words: "escort" (ford escort, police escort), "hardcore" (hardcore punk), "creampie" (recipe slugs), "fetish" (`wikipedia.org/wiki/Commodity_fetishism`, and note ALLOWLIST exempts the DOMAIN match only, so Wikipedia was never protected from the keyword layer).

Fixed by replacing the blob with 486 hand-curated domains (12KB). Curation was data-driven rather than from memory: the blob was intersected with the Tranco top-1M so its entries could be ranked by real traffic, that ranking was hand-reviewed (the gov/edu false positives all sat in the top 5k, the ranking surfaces them for free), then cross-checked against current Semrush category rankings. 458 of the 486 are corroborated by the old blob; the rest are newer sites it predates.

Rules this repo now runs on:
- **Curation policy: precision over recall.** A false positive on a government or university site is far worse than missing adult site #4000. It is written into the asset's own `#` header (`ContentFilterRepository.parseLine` skips comments) as well as CLAUDE.md.
- **The domain list only exists for names the keyword layer can't see.** `matchesKeyword` substring-matches the full URL, so anything containing porn/xxx/hentai/xvideos already blocks. Spend curation effort on bangbros/beeg/e621/missav/coomer-class names.
- **Never list CDNs, ad networks or hosting.** The filter reads the browser URL bar, nobody navigates to a CDN, so they are pure false-positive risk with zero blocking value.
- **`ContentFilterAssetTest` gates the SHIPPED file**, parsed with the app's own parser: ≤3,000-entry/<1MB ceiling (the guard against pasting a blob back in), no `.gov`/`.edu`/`.mil`/`.int`/`.ac.*`, no public-suffix entry (`co.uk` would block every British site), no ALLOWLIST collision, no parent-redundant entry, and a ~65-domain benign corpus that must match via neither layer. Tests over a fake fixture could never have caught this class.
- **New keyword test: would this plausibly appear in a benign URL, or in a search a normal person makes?** If yes it doesn't go in, and it does NOT get demoted to `AMBIGUOUS_QUERY_KEYWORDS` either unless the whole-word-in-query form is genuinely safe ("ford escort review" still contains the whole word "escort").

## Verifying a data-migration feature on a RELEASE install: sign debug with the release key (2026-08-20)

Device-verifying the "export/import carries usage history" change needed three things a release build won't give you: the exported file's bytes, the DB's real row counts, and a corpus worth transferring. The corpus lived in the RELEASE install on the Pixel (19 blocked / 7 walked away), and a release build is not `run-as`-able, so neither the file nor the DB could be read; installing a debug build over it fails on signature mismatch, and uninstalling first destroys the very corpus under test.

The way through: **temporarily point the debug build type at the release signing config** (`debug { signingConfig = signingConfigs.getByName("release") }`), `assembleDebug`, then `adb install -r` over the release install. Same signature so the data survives, `debuggable` so `run-as` works. From there: `adb shell "run-as dev.astraedus.nudge cat databases/nudge.db"` (plus the `-wal`, or the DB reads as an empty 4KB file) for ground truth, and `adb exec-out "run-as … cat cache/nudge-rules-export.json"` to pull the exported file the app actually wrote. Then push that file to `/sdcard/Download`, uninstall, install the real RELEASE APK, and import through the normal UI. Revert the gradle change immediately (`git checkout app/build.gradle.kts`), it must never be committed.

Two traps this walked into, worth pre-empting next time:
- **A fresh install can silently restore an old cloud backup** (`allowBackup=true`), which would fake a passing restore test. Always confirm the tiles read 0 BEFORE importing.
- **Only ONE agent may drive the device.** Running a device-tester agent and driving ADB from the orchestrator at the same time produced phantom symptoms on both sides (a rule toggle "flipping by itself", back presses "not working", the file picker "opening on its own") and cost a good ten minutes of misdiagnosis. Hand the device over explicitly, and wait for the acknowledgement before touching it.
- A11y-service state is not restored by finishing onboarding: after any uninstall/reinstall, re-enable it explicitly (`astra-adb accessibility-enable dev.astraedus.nudge/com.astraedus.nudge.service.NudgeAccessibilityService`) and check `dumpsys accessibility` shows it under **Bound services**, not just Enabled, and remember the home-screen MASTER TOGGLE is a second, independent switch. A fresh install with the master toggle off blocks nothing, which looks exactly like a broken build.

## An early return added for surface X also skips every cleanup below it (2026-08-27)

The delay-to-open feature was silently one-shot per app for every user with a stock launcher: complete YouTube's delay, press Home, reopen YouTube, no delay, forever. `onAccessibilityEvent`'s `if (packageName in SYSTEM_PACKAGES) { clearOverlays(); return }` sits ~200 lines above `PassthroughManager.clearIfAppChanged`, and the stock launchers are in `SYSTEM_PACKAGES`. Nothing about the passthrough logic was wrong; it was simply never reached on the most common exit path there is.

The class, and how to catch it next time:
- **When you add or extend an early return in a long event handler, enumerate what is BELOW it and ask which of those cleanups the new short-circuit now silently skips.** This service has four of them stacked (PiP-only, overlay-active, own-package, transient-window, SYSTEM_PACKAGES) and each one is a place a downstream invariant can quietly stop running. Grep the function for every `return` before adding another.
- **A grouped constant set is a decision you inherit without noticing.** `SYSTEM_PACKAGES` bundles the launcher (= the user LEFT the app) with SystemUI / IME / permission dialogs (= transient, the user did NOT leave). One membership test answered two different questions. Same defect shape as `hasEntry` vs `isCounterEnabled` (v1.10.0) and issue #5's hardcoded IME list.
- **Fix direction matters more than the fix.** Clearing for *every* system package would have fixed the report and re-delayed users for pulling the notification shade, worse than the bug. The allowlist (resolve the launcher, clear only for it) fails toward the OLD behaviour when resolution fails or goes stale.
- **`CATEGORY_HOME` does not mean "launcher".** On stock AOSP `queryIntentActivities` returns `com.android.settings` (`Settings$FallbackHome`, the pre-unlock placeholder home) and, with no default home set, `resolveActivity` returns the framework's ResolverActivity in package `android`. Both must be filtered or Settings becomes "home".
- **When the bug is WHERE code sits, only a source-level test can see it.** Value-level unit tests over the extracted pure function pass just as happily with the call site deleted. `HomeScreenPassthroughContractTest` asserts the ordering itself (verified to fail with the one call line removed), the same tool `BlockOverlayWalkAwayContractTest` and `ImportedSettingsWriteContractTest` already use.
