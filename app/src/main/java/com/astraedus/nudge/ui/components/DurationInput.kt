package com.astraedus.nudge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Pure helpers behind the free-form "minutes" inputs used by the auto-kick controls.
 *
 * The UI works in MINUTES because that is how users think about "use it for 30 minutes, then block
 * it for 15" ([issue #6](https://github.com/astraedus/nudge/issues/6)). The database stores the
 * auto-kick cooldown in SECONDS (`BlockRule.autoKickCooldownSeconds`), so the conversion lives here
 * — one place, unit-tested — rather than being re-derived at each of the three editor sites.
 *
 * No Android imports in the helpers: [DurationInput] is fully JVM-testable.
 */
object DurationInput {

    /** 24h. Anything longer is a "block this app" rule, not a cooldown. */
    const val MAX_MINUTES = 1440

    /** Digits in [MAX_MINUTES]; caps what the field will accept so parsing never sees a huge string. */
    private const val MAX_DIGITS = 4

    /**
     * Filters raw field input down to what a minutes field may contain: digits only, bounded length.
     * Anything else (letters, signs, decimal points, paste of a whole paragraph) is dropped rather
     * than rejected, so typing never appears to "freeze".
     */
    fun sanitize(input: String): String = input.filter { it.isDigit() }.take(MAX_DIGITS)

    /**
     * Field text -> a usable minutes threshold, or null when the field means "off".
     *
     * Null for blank, non-numeric, and **zero** — "kick after 0 minutes" is not a threshold a user
     * can mean, and treating it as one would kick them out the instant they opened the app.
     * Over-large values are clamped to [maxMinutes] rather than rejected.
     */
    fun parseMinutes(text: String, maxMinutes: Int = MAX_MINUTES): Int? {
        val value = text.trim().toIntOrNull() ?: return null
        if (value <= 0) return null
        return value.coerceAtMost(maxMinutes)
    }

    /**
     * True when the user has typed something that cannot be used — for the error state on the field.
     * A blank field is NOT an error: blank is the documented way to turn the trigger off.
     */
    fun isInvalid(text: String, maxMinutes: Int = MAX_MINUTES): Boolean {
        if (text.isBlank()) return false
        val value = text.trim().toIntOrNull() ?: return true
        return value <= 0 || value > maxMinutes
    }

    /**
     * Field text -> the seconds value persisted in `BlockRule.autoKickCooldownSeconds`.
     * Blank / invalid / zero all mean "no cooldown" (0), matching the old slider's "Off" position.
     */
    fun cooldownSecondsFromText(text: String, maxMinutes: Int = MAX_MINUTES): Int =
        (parseMinutes(text, maxMinutes) ?: 0) * 60

    /**
     * Stored seconds -> the minutes text shown in the field.
     *
     * 0 renders BLANK (the field's "off" state). Any other value rounds **up** to a whole minute.
     * Rounding is unavoidable: the field's unit is minutes and real stored values are not always
     * whole minutes — the 0-300s slider this input replaces had `steps = 5` over `0f..300f`, which
     * Compose resolves to the seven stops 0/50/100/150/200/250/300, so 50s and 150s cooldowns exist
     * in the wild (the old code comment claiming 0/60/120/180/240/300 was simply wrong). Rounding
     * UP is chosen because a 50s cooldown displayed as "0" would look like "off", and rounding down
     * could only ever shorten protection.
     *
     * Rounding is display-only. Persisting goes through [resolveCooldownSeconds], which preserves
     * the exact stored value when the user did not edit the field, so opening an editor and saving
     * an unrelated change can never silently rewrite 150s to 180s.
     */
    fun cooldownSecondsToText(seconds: Int): String {
        if (seconds <= 0) return ""
        val minutes = (seconds + 59) / 60
        return minutes.coerceAtMost(MAX_MINUTES).toString()
    }

    /** Stored minutes (nullable) -> field text. Null or non-positive renders blank. */
    fun minutesToText(minutes: Int?): String =
        if (minutes == null || minutes <= 0) "" else minutes.toString()

    /**
     * The cooldown seconds to persist.
     *
     * When the field still reads exactly what [cooldownSecondsToText] rendered for
     * [originalSeconds], the user did not touch it and the ORIGINAL value is returned verbatim —
     * displayed rounding never leaks into storage. Only a genuine edit re-derives the value from
     * the text.
     *
     * This matters beyond tidiness: `RuleWeakening` treats a LOWERED cooldown as a protection
     * weakening that demands the Strict Mode unlock challenge. Letting a round-trip nudge the
     * stored value would rewrite users' data behind their backs and could raise the challenge on an
     * edit that changed something else entirely.
     */
    fun resolveCooldownSeconds(
        text: String,
        originalSeconds: Int,
        maxMinutes: Int = MAX_MINUTES
    ): Int = if (text == cooldownSecondsToText(originalSeconds)) {
        originalSeconds
    } else {
        cooldownSecondsFromText(text, maxMinutes)
    }

    /**
     * The minutes threshold to persist, with the same untouched-field guarantee as
     * [resolveCooldownSeconds]. [minutesToText] is lossless for every value this UI can produce, so
     * this is belt-and-braces — but it also means a value that arrived from an import outside
     * 1..[MAX_MINUTES] survives an unrelated save instead of being silently clamped.
     */
    fun resolveMinutes(
        text: String,
        originalMinutes: Int?,
        maxMinutes: Int = MAX_MINUTES
    ): Int? = if (text == minutesToText(originalMinutes)) {
        originalMinutes
    } else {
        parseMinutes(text, maxMinutes)
    }
}

/**
 * Free-form numeric minutes field. Blank is always a legal value and means "off" — callers convert
 * with [DurationInput.parseMinutes] / [DurationInput.cooldownSecondsFromText].
 *
 * Styled to match the surrounding editors (outlined field, label above via [labelText] so it reads
 * like the other section labels, compact supporting copy underneath).
 */
@Composable
fun MinutesField(
    value: String,
    onValueChange: (String) -> Unit,
    labelText: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    maxMinutes: Int = DurationInput.MAX_MINUTES
) {
    val invalid = DurationInput.isInvalid(value, maxMinutes)

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(DurationInput.sanitize(it)) },
            label = { Text(labelText) },
            suffix = { Text("min") },
            placeholder = { Text("Off") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            singleLine = true,
            isError = invalid,
            modifier = Modifier.fillMaxWidth()
        )
        val helper = when {
            invalid -> "Enter 1-$maxMinutes minutes, or leave blank for off"
            supportingText != null -> supportingText
            else -> null
        }
        if (helper != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                helper,
                style = MaterialTheme.typography.bodySmall,
                color = if (invalid) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
