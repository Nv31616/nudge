package com.astraedus.nudge.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table-style coverage of [WebBlockMode.resolve] (issue #21): a rule's web domains must be
 * gated by their OWN resolved mode, not blindly inherit the app-level mode.
 */
class WebBlockModeTest {

    @Test
    fun `null webBlockMode inherits HARD_BLOCK app mode`() {
        assertEquals(BlockMode.HARD_BLOCK, WebBlockMode.resolve("HARD_BLOCK", null))
    }

    @Test
    fun `null webBlockMode inherits DELAY app mode`() {
        assertEquals(BlockMode.DELAY, WebBlockMode.resolve("DELAY", null))
    }

    @Test
    fun `null webBlockMode inherits BREATHING app mode`() {
        assertEquals(BlockMode.BREATHING, WebBlockMode.resolve("BREATHING", null))
    }

    @Test
    fun `null webBlockMode inherits NONE app mode`() {
        assertEquals(BlockMode.NONE, WebBlockMode.resolve("NONE", null))
    }

    @Test
    fun `set webBlockMode wins over a different app mode`() {
        assertEquals(BlockMode.DELAY, WebBlockMode.resolve("HARD_BLOCK", "DELAY"))
    }

    @Test
    fun `set webBlockMode HARD_BLOCK wins over app mode NONE -- the issue 21 case`() {
        assertEquals(BlockMode.HARD_BLOCK, WebBlockMode.resolve("NONE", "HARD_BLOCK"))
    }

    @Test
    fun `set webBlockMode BREATHING wins over app mode DELAY`() {
        assertEquals(BlockMode.BREATHING, WebBlockMode.resolve("DELAY", "BREATHING"))
    }

    @Test
    fun `set webBlockMode NONE wins even over an enforcing app mode`() {
        assertEquals(BlockMode.NONE, WebBlockMode.resolve("HARD_BLOCK", "NONE"))
    }

    @Test
    fun `unrecognized webBlockMode falls back to the app mode`() {
        assertEquals(BlockMode.DELAY, WebBlockMode.resolve("DELAY", "GARBAGE"))
    }

    @Test
    fun `blank webBlockMode falls back to the app mode`() {
        assertEquals(BlockMode.BREATHING, WebBlockMode.resolve("BREATHING", ""))
    }

    @Test
    fun `unreadable webBlockMode and unreadable ruleMode both fail toward HARD_BLOCK`() {
        assertEquals(BlockMode.HARD_BLOCK, WebBlockMode.resolve("GARBAGE", "ALSO_GARBAGE"))
    }

    @Test
    fun `both modes null fails toward HARD_BLOCK`() {
        assertEquals(BlockMode.HARD_BLOCK, WebBlockMode.resolve(null, null))
    }

    @Test
    fun `unrecognized webBlockMode with unrecognized ruleMode fails toward HARD_BLOCK`() {
        assertEquals(BlockMode.HARD_BLOCK, WebBlockMode.resolve("NONSENSE", "NONSENSE"))
    }

    @Test
    fun `null webBlockMode with unreadable ruleMode fails toward HARD_BLOCK`() {
        assertEquals(BlockMode.HARD_BLOCK, WebBlockMode.resolve("NONSENSE", null))
    }
}
