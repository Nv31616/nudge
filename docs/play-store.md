# Google Play compliance and store listing

Covers the AccessibilityService prominent-disclosure requirement (a Play policy gate we have already been
rejected on) and where the listing assets live.
**Read before touching onboarding, the Settings permission flow, or the store listing.**

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
