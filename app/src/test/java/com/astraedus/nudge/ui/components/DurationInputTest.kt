package com.astraedus.nudge.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the pure minutes-field helpers behind the auto-kick "time in app" / cooldown inputs
 * ([issue #6](https://github.com/astraedus/nudge/issues/6)). No Android deps, so this runs on the
 * plain JVM.
 */
class DurationInputTest {

    // ── sanitize() ──

    @Test
    fun `sanitize strips letters spaces signs and decimal points`() {
        assertEquals("30", DurationInput.sanitize("3 0"))
        assertEquals("30", DurationInput.sanitize("3a0b"))
        assertEquals("30", DurationInput.sanitize("-30"))
        assertEquals("30", DurationInput.sanitize("+30"))
        assertEquals("305", DurationInput.sanitize("30.5"))
    }

    @Test
    fun `sanitize caps at 4 digits`() {
        assertEquals("1234", DurationInput.sanitize("123456"))
    }

    @Test
    fun `sanitize of empty stays empty`() {
        assertEquals("", DurationInput.sanitize(""))
    }

    // ── parseMinutes() ──

    @Test
    fun `parseMinutes parses a plain number`() {
        assertEquals(30, DurationInput.parseMinutes("30"))
    }

    @Test
    fun `parseMinutes returns null for blank`() {
        assertNull(DurationInput.parseMinutes(""))
    }

    @Test
    fun `parseMinutes returns null for non-numeric text`() {
        assertNull(DurationInput.parseMinutes("abc"))
    }

    @Test
    fun `parseMinutes returns null for zero`() {
        // Zero is "off", never a real threshold -- a 0-minute kick would fire instantly.
        assertNull(DurationInput.parseMinutes("0"))
    }

    @Test
    fun `parseMinutes clamps an over-large value to maxMinutes`() {
        assertEquals(1440, DurationInput.parseMinutes("99999"))
    }

    @Test
    fun `parseMinutes tolerates leading and trailing whitespace`() {
        assertEquals(30, DurationInput.parseMinutes(" 30 "))
        assertEquals(30, DurationInput.parseMinutes("\t30\n"))
    }

    // ── isInvalid() ──

    @Test
    fun `isInvalid is false for blank`() {
        // Blank is the documented way to turn the trigger off -- not an error state.
        assertFalse(DurationInput.isInvalid(""))
    }

    @Test
    fun `isInvalid is true for zero`() {
        assertTrue(DurationInput.isInvalid("0"))
    }

    @Test
    fun `isInvalid is true for non-numeric text`() {
        assertTrue(DurationInput.isInvalid("abc"))
    }

    @Test
    fun `isInvalid is true above maxMinutes`() {
        assertTrue(DurationInput.isInvalid("1441"))
    }

    @Test
    fun `isInvalid is false for an ordinary value`() {
        assertFalse(DurationInput.isInvalid("30"))
    }

    // ── cooldownSecondsFromText() ──

    @Test
    fun `cooldownSecondsFromText converts minutes to seconds`() {
        assertEquals(900, DurationInput.cooldownSecondsFromText("15"))
    }

    @Test
    fun `cooldownSecondsFromText is zero for blank`() {
        assertEquals(0, DurationInput.cooldownSecondsFromText(""))
    }

    @Test
    fun `cooldownSecondsFromText is zero for non-numeric text`() {
        assertEquals(0, DurationInput.cooldownSecondsFromText("abc"))
    }

    @Test
    fun `cooldownSecondsFromText is zero for zero`() {
        assertEquals(0, DurationInput.cooldownSecondsFromText("0"))
    }

    // ── cooldownSecondsToText() ──

    @Test
    fun `cooldownSecondsToText renders zero as blank`() {
        assertEquals("", DurationInput.cooldownSecondsToText(0))
    }

    @Test
    fun `cooldownSecondsToText renders whole minutes exactly`() {
        assertEquals("1", DurationInput.cooldownSecondsToText(60))
        assertEquals("5", DurationInput.cooldownSecondsToText(300))
    }

    @Test
    fun `cooldownSecondsToText rounds sub-minute and part-minute values UP`() {
        // Rounding DOWN would silently delete a live cooldown on the next save (e.g. an imported
        // 30s cooldown would display as "0" == off). Rounding up means a re-save can only ever
        // lengthen a cooldown, never shorten or erase one -- the protection-preserving direction.
        assertEquals("1", DurationInput.cooldownSecondsToText(30))
        assertEquals("2", DurationInput.cooldownSecondsToText(90))
    }

    // ── minutesToText() ──

    @Test
    fun `minutesToText renders null as blank`() {
        assertEquals("", DurationInput.minutesToText(null))
    }

    @Test
    fun `minutesToText renders zero as blank`() {
        assertEquals("", DurationInput.minutesToText(0))
    }

    @Test
    fun `minutesToText renders a positive value`() {
        assertEquals("30", DurationInput.minutesToText(30))
    }

    @Test
    fun `minutesToText does not clamp an out-of-range value`() {
        // Unlike parseMinutes, minutesToText displays the truth so an untouched field round-trips
        // an imported value exactly (see resolveMinutes) instead of silently clamping it on display.
        assertEquals("5000", DurationInput.minutesToText(5000))
    }

    // ── round-trip invariant ──

    @Test
    fun `cooldown text round-trips for every whole-minute value the old slider or new field can produce`() {
        for (seconds in listOf(0, 60, 120, 300, 900, 3600)) {
            val text = DurationInput.cooldownSecondsToText(seconds)
            assertEquals(seconds, DurationInput.cooldownSecondsFromText(text))
        }
    }

    // ── resolveCooldownSeconds() ──

    @Test
    fun `resolveCooldownSeconds preserves off-grid values the old slider could produce when untouched`() {
        // The old 0-300s slider had steps=5 over 0f..300f, which Compose resolves to the seven
        // stops 0/50/100/150/200/250/300 -- so 50s/150s/250s cooldowns exist in the wild. A naive
        // text->seconds conversion on an unrelated save would rewrite 150s to the rounded-up "3min"
        // (180s) every time. Feeding the field exactly what cooldownSecondsToText displayed for the
        // stored value must return the ORIGINAL seconds, not the rounded reinterpretation.
        for (seconds in listOf(50, 90, 150, 250)) {
            val displayedText = DurationInput.cooldownSecondsToText(seconds)
            assertEquals(seconds, DurationInput.resolveCooldownSeconds(displayedText, seconds))
        }
    }

    @Test
    fun `resolveCooldownSeconds re-derives from the text on a genuine edit`() {
        assertEquals(300, DurationInput.resolveCooldownSeconds("5", 150))
    }

    @Test
    fun `resolveCooldownSeconds treats a cleared field as turning the cooldown off`() {
        assertEquals(0, DurationInput.resolveCooldownSeconds("", 150))
    }

    @Test
    fun `resolveCooldownSeconds preserves a brand-new rule's default`() {
        assertEquals(60, DurationInput.resolveCooldownSeconds("1", 60))
    }

    // ── resolveMinutes() ──

    @Test
    fun `resolveMinutes preserves the original when the field is untouched`() {
        assertEquals(30, DurationInput.resolveMinutes(DurationInput.minutesToText(30), 30))
        assertNull(DurationInput.resolveMinutes("", null))
    }

    @Test
    fun `resolveMinutes preserves an out-of-range imported value when untouched`() {
        assertEquals(5000, DurationInput.resolveMinutes(DurationInput.minutesToText(5000), 5000))
    }

    @Test
    fun `resolveMinutes re-derives and clamps from the text on a genuine edit`() {
        assertEquals(45, DurationInput.resolveMinutes("45", 30))
    }

    @Test
    fun `resolveMinutes treats a cleared field as turning the trigger off`() {
        assertNull(DurationInput.resolveMinutes("", 30))
    }
}
