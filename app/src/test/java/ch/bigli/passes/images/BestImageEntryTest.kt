package ch.bigli.passes.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BestImageEntryTest {
    private val entries = setOf("pass.json", "logo@2x.png", "logo@3x.png", "strip@2x.png")

    @Test fun `prefers @3x over @2x`() {
        assertEquals("logo@3x.png", bestImageEntry(entries, "logo"))
    }

    @Test fun `falls back to @2x when @3x absent`() {
        assertEquals("strip@2x.png", bestImageEntry(entries, "strip"))
    }

    @Test fun `falls back to base name when only base present`() {
        assertEquals("logo.png", bestImageEntry(setOf("logo.png"), "logo"))
    }

    @Test fun `returns null when image absent`() {
        assertNull(bestImageEntry(entries, "thumbnail"))
    }
}
