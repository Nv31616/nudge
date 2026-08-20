package com.astraedus.nudge.ui.overlay

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.astraedus.nudge.ui.theme.NudgeTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Explains issue #19 to the user: when Nudge's block overlay backgrounds an app like YouTube, the
 * app can enter picture-in-picture and keep playing a Short in a floating window on top of the
 * overlay. The overlay is correctly full-screen and the topmost resumed activity — PiP is a
 * platform feature that lets another app's window float above everything, and there is no public
 * API for Nudge to disable PiP for another app itself. The only real fix is the OS-level per-app
 * "Picture-in-picture" toggle, which only the user can flip. This screen is shown once per app
 * (the accessibility service tracks which apps have already seen it) and deep-links to that
 * toggle via [PipSettings], degrading to manual instructions when the deep link can't resolve.
 *
 * Stats-honesty invariant: a PiP escape is NEITHER a block NOR a walk-away. This activity does not
 * touch [com.astraedus.nudge.service.NudgeAccessibilityService.isOverlayActive], does not log any
 * [com.astraedus.nudge.data.db.entity.UsageEvent], and does not grant passthrough — it stands in
 * for a block the overlay already lost, and recording it as one would misreport what happened. A
 * future edit must not add any of those three without re-checking this contract.
 */
@AndroidEntryPoint
class PipEscapeActivity : ComponentActivity() {

    private var resolvedTarget: PipSettingsTarget? = null

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"

        /**
         * True while the explainer is on screen. The accessibility service reads this to keep
         * swallowing events for the blocked app: this screen STANDS IN for the block overlay, so
         * re-evaluating behind it would relaunch the overlay on top of the explainer AND log a
         * second wasBlocked usage event for a block the user never re-triggered.
         */
        @Volatile
        var isActive: Boolean = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActive = true

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { null }

        val target = PipSettings.firstResolvable { candidate ->
            buildIntent(candidate, packageName).resolveActivity(packageManager) != null
        }
        resolvedTarget = target

        setContent {
            NudgeTheme {
                PipEscapeContent(
                    appLabel = appLabel,
                    packageName = packageName,
                    canOpenSettings = target != null,
                    onOpenSettings = { onOpenSettings(packageName) },
                    onDismiss = { onDismiss() }
                )
            }
        }
    }

    private fun buildIntent(target: PipSettingsTarget, packageName: String): Intent =
        Intent(target.action).apply {
            if (target.usePackageUri) {
                data = Uri.fromParts("package", packageName, null)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun onOpenSettings(packageName: String) {
        val target = resolvedTarget
        if (target != null) {
            try {
                startActivity(buildIntent(target, packageName))
            } catch (_: ActivityNotFoundException) {
                // Resolution was checked at onCreate time, but the OS state (or OEM behavior) can
                // still disagree at launch time. Never crash the user out of this screen for it.
            }
        }
        dismiss()
    }

    /**
     * Close, clearing [isActive] FIRST.
     *
     * The service suspends all enforcement while that flag is set (this screen stands in for the
     * block overlay). Clearing it only in [onDestroy] would leave enforcement paused for the gap
     * between `finish()` and the system actually destroying us — a small but real window in which a
     * blocked app would not be blocked. Same reasoning as [BlockOverlayActivity.onStop], which
     * clears the overlay flag before finishing rather than waiting for teardown. [onDestroy] keeps
     * clearing it as a backstop for paths that never route through here (e.g. a process-level kill
     * of the task).
     */
    private fun dismiss() {
        isActive = false
        finish()
    }

    /**
     * Back / "Not now" / dismiss: just close. The block overlay this screen replaced is already
     * gone, so there is nothing left to protect and nowhere the user needs to be routed — unlike
     * [com.astraedus.nudge.ui.lock.StrictModeGuardActivity], forcing them home here would be
     * bouncing them out of an app they were never trying to escape.
     */
    private fun onDismiss() {
        dismiss()
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        onDismiss()
    }

    /**
     * Same discipline as [BlockOverlayActivity.onStop]: leaving this screen (Home, recents, screen
     * off) must not leave an orphaned task lingering in this singleInstance/empty-taskAffinity
     * activity.
     */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) {
            dismiss()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
    }
}
