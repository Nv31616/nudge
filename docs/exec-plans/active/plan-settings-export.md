# Plan — app settings ride in the export file

Goal: an export/import backup carries the user's app SETTINGS (custom block messages + the
on/off + strictness switches), not just rules and history.

## Format (stays `version: 1`)

New OPTIONAL top-level `settings` OBJECT, added exactly the way `history` was: every shipped
importer reads the envelope by KNOWN KEY ONLY, so an older Nudge ignores it, while a version
bump would make every older build reject the whole file and cost the user their RULES.

Two-level failure policy, matching the existing keys:
- envelope level: `settings` present but not an object = this is not a Nudge export -> loud error
  (identical to `rules`/`groups`/`history` not being arrays).
- key level: one wrong-typed or out-of-range setting is SKIPPED and counted, never fatal (issue
  #20 shape). A key that is ABSENT means "not carried" -> the device's own value is untouched,
  which is also how a future Nudge adds a tenth setting without breaking this build.

## What is carried

Carried (9): `customDelayTitles`, `customDelaySubtitles`, `customHardBlockMessages`,
`contentFilterEnabled`, `contentFilterMode`, `contentFilterStrictKeywords`, `strictModeEnabled`,
`strictModeChallengeLength`, `emergencyPassEnabled`.

Excluded, deliberately:
- `ONBOARDING_COMPLETE`, `PIP_ESCAPE_PROMPTED`, `EMERGENCY_PASS_USAGE`, `DEBUG_LOGGING_ENABLED`
  — device-local state, not configuration.
- `GLOBAL_ENABLED` — see the note in `RuleExportData.ExportedSettings`. Restoring `true` onto a
  fresh device is a no-op (it already defaults to true), so the ONLY behaviour including it adds
  is the ability for a file to switch the whole blocker OFF. Pure downside.

## Security: import must not become a Strict Mode bypass

- `domain/lock/ImportedSettingsWeakening` — pure policy, sibling of `RuleWeakening` /
  `SettingsWeakening`. Reuses `RuleWeakening.modeStrength` for `contentFilterMode`.
- `ImportRulesUseCase.weakensProtection(result)` reads LIVE prefs and asks the policy.
- `ActiveRulesViewModel.confirmImport` routes the whole import through the existing
  `StrictModeGate` when it weakens — the same call shape as `toggleAppEnabled`. Gating the WHOLE
  import (not just the settings step) is the fail-closed choice and avoids a half-applied import.
- Independent hardening: an imported `strictModeChallengeLength` is range-checked against
  `StrictModeChallenge.MAX_LENGTH`. A file could otherwise install a 100,000-character challenge,
  which is a permanent lockout, not a lock — it breaks the documented invariant that the challenge
  is always solvable.

## All-or-nothing

`NudgePreferences.applyImportedSettings` writes every carried key inside ONE `dataStore.edit {}`
— one transaction, same discipline as the single-`insertAll` history restore.

## Steps

1. `ExportedSettings` + `NudgeExport.settings` (RuleExportData).
2. `RuleExporter` serialize + parse (per-key isolation, its own skip counter).
3. `NudgePreferences.exportableSettings()` / `.applyImportedSettings()`.
4. `ImportedSettingsWeakening` + `StrictModeChallenge.MAX_LENGTH`.
5. `ExportRulesUseCase` / `ImportRulesUseCase` wiring + `ImportOutcome` fields.
6. `ActiveRulesViewModel` gate + `ImportMessages` wording.
7. Tests: round trip, old-file compat, brace-splice with `}` in a custom message, per-key skips,
   challenge-length clamp, weakening matrix, gate behaviour, all-or-nothing write.
8. Docs: CLAUDE.md export section + CHANGELOG.
