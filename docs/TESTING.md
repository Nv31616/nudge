# Testing philosophy and coverage targets

The full version of the rule summarised in `CLAUDE.md`. **Read when deciding what a change owes in tests.**

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
