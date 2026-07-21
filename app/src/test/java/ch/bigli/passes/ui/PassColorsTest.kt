package ch.bigli.passes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PassColorsTest {
    @Test fun `white text on white background falls back to black`() {
        assertEquals(0xFF000000, legibleTextColor(background = 0xFFFFFFFF, requested = 0xFFFFFFFF))
    }

    @Test fun `black text on black background falls back to white`() {
        assertEquals(0xFFFFFFFF, legibleTextColor(background = 0xFF000000, requested = 0xFF000000))
    }

    @Test fun `well-contrasting requested color is preserved`() {
        // white on SWISS blue already contrasts well -> keep it
        assertEquals(0xFFFFFFFF, legibleTextColor(background = 0xFF1A73E8, requested = 0xFFFFFFFF))
    }

    @Test fun `null requested color derives from background luminance`() {
        assertEquals(0xFF000000, legibleTextColor(background = 0xFFFFFFFF, requested = null))
        assertEquals(0xFFFFFFFF, legibleTextColor(background = 0xFF1A73E8, requested = null))
    }
}
