package com.astraedus.nudge.ui.overlay

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.emergency.EmergencyPass

/**
 * Content-filter blocks are tracked under this synthetic package (see `EvaluateBlockUseCase`); there
 * is no real app to grant a free window on, so the pass never renders for them.
 */
private const val WEB_PSEUDO_PACKAGE = "web"

/**
 * Resolved state of the daily-pass action for one block overlay. [canUse] and [locked] are mutually
 * exclusive; both false means nothing renders at all.
 */
internal data class EmergencyPassUiState(
    val canUse: Boolean = false,
    val locked: Boolean = false,
    val nextPassMs: Long = 0L
)

/**
 * Pure decision for whether the daily-pass action shows on a block overlay, and in which state.
 * Pure so it is JVM-unit-testable (`EmergencyPassVisibilityTest`) rather than buried in the Activity.
 *
 * **Strict Mode is deliberately NOT an input (v1.10.0).** It used to hide the pass outright, which
 * silently revoked an escape hatch the user had explicitly opted into. The commitment lock now bites
 * at the point protection is WEAKENED — turning the toggle back ON in Settings is challenge-gated
 * ([com.astraedus.nudge.domain.lock.SettingsWeakening]) — so availability here is the user's own
 * toggle plus the global 24h lockout, and nothing else.
 */
internal fun resolveEmergencyPassState(
    packageName: String,
    passEnabled: Boolean,
    usage: Map<String, Long>,
    now: Long,
    lockoutMs: Long = EmergencyPass.LOCKOUT_MS
): EmergencyPassUiState {
    if (packageName.isBlank() || packageName == WEB_PSEUDO_PACKAGE) return EmergencyPassUiState()
    if (!passEnabled) return EmergencyPassUiState()
    return if (EmergencyPass.canUseGlobal(usage, now, lockoutMs)) {
        EmergencyPassUiState(canUse = true)
    } else {
        EmergencyPassUiState(
            locked = true,
            nextPassMs = EmergencyPass.nextAvailableGlobalMs(usage, now, lockoutMs)
        )
    }
}

/**
 * The "2-minute daily pass" escape-hatch action shown on every block overlay, below the primary
 * "Go Back" / "I changed my mind" button and above the "Rule: X" footer.
 *
 * Deliberately understated (a muted [TextButton]) so it reads as a last resort, not a primary CTA.
 * When the feature is off, the caller passes [canUse] = [locked] = false and nothing renders. When
 * the pass is spent (globally, across all apps), a visibly DISABLED (grey) button renders — NOT
 * hidden — with the "Daily pass used · next in Xh" hint, so the user can see the escape exists but is
 * used up for the day. See [resolveEmergencyPassState] for how those flags are decided.
 */
@Composable
fun EmergencyPassAction(
    canUse: Boolean,
    locked: Boolean,
    nextPassMs: Long,
    onUse: () -> Unit
) {
    when {
        canUse -> {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onUse) {
                Text(
                    text = "Use for 2 minutes · once a day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        locked -> {
            Spacer(modifier = Modifier.height(8.dp))
            // Disabled TextButton greys its content automatically — a visible "used up" control
            // rather than a hidden one, so the user knows the daily pass exists but is spent.
            TextButton(onClick = {}, enabled = false) {
                Text(
                    text = "Daily pass used · next in ${formatDuration(nextPassMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
