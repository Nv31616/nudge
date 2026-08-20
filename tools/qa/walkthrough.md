# Instagram Detection QA Walkthrough (issues #18, #22)

Run this WHILE `tools/qa/ig-walkthrough-capture.sh` is running in a terminal — it does the
screenshotting/logging, you do the tapping. It will pause on ENTER between every step below;
read the step, do it, then hit ENTER in the terminal.

Whole thing takes about 5 minutes. Nothing here needs precision — real usage is exactly the
point (the QA is about whether *natural* Instagram use trips detection correctly).

## 0. Setup (before the script starts capturing)

1. Open **Nudge**.
2. **Settings** → tap **"Version"** 7 times → a new **"Developer Options"** section appears.
3. Turn **"Debug Logging"** ON (switch next to it).
   - Skipping this means the script's captures will come back empty even if everything
     is working — Nudge only writes these diagnostic logs when this is on.
4. Go to your rules and confirm (or create) one for **Instagram**:
   - **Reels** feature-blocking is turned ON for it.
   - Block mode is **DELAY** or **HARD_BLOCK** — not disabled/off.
   - No daily time limit set on this rule (limits can mask whether detection itself fired).
5. Leave that rule's config screen open — the script's first prompt screenshots it.

Run the script now: `tools/qa/ig-walkthrough-capture.sh`. Follow its prompts; they mirror the
cases below in order.

## The six cases (~30s each)

### a. Reels tab (issue #18)
Open Instagram. Tap the **Reels** tab at the bottom. Let a reel play for ~10 seconds.
**Expect:** Nudge's block overlay (delay countdown or hard-block screen) appears.

### b. Home feed (issue #18)
From Instagram's home feed, scroll down until a reel or video clip starts playing — either
inline in the feed or full-screen. Keep scrolling/watching for ~30 seconds total.
**Expect:** unclear going in — this is exactly what we're checking. Note whether the overlay
ever appears while you're just scrolling the normal home feed.

### c. Explore (issue #18)
Tap **Explore** (magnifying glass). Browse the grid for a few seconds, then **tap into a video**
from the grid and let it play.
**Expect:** unclear going in — note whether the overlay appears once you're actually watching
a video from Explore.

### d. Explore grid, no video (issue #22 — false positive check)
Tap **Explore** again. This time just **scroll the grid** for ~30 seconds — do NOT tap into any
video or post.
**Expect:** NO overlay should appear. If one does, that's a false positive — note when.

### e. Stories (issue #22 — false positive check)
Tap into someone's **Story** from the top row and watch 2-3 stories through.
**Expect:** NO overlay should appear.

### f. Reels tab then away (issue #22 — false positive check)
Tap the **Reels** tab, then **immediately** tap back to **Home** before a reel has fully loaded
(don't wait for it to play). Then just use the home feed normally for ~30 seconds.
**Expect:** NO overlay should appear late/stuck on the home feed from the aborted Reels-tab
visit. If the overlay appears at a weird moment or won't dismiss, note exactly when.

## When done

The script prints where everything landed (`~/Pictures/screenshots/nudge-qa-ig-*`). You don't
need to do anything else with the files — hand off to whoever's doing the writeup.

If anything felt ambiguous mid-walk (e.g. you weren't sure if a video "counted" as playing, or
the overlay appeared but you're not sure which case triggered it), just say so when you report
back — that's useful signal, not noise.
