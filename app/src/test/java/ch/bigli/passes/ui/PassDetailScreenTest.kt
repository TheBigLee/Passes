package ch.bigli.passes.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassDetailScreenTest {
    @Test fun `a bare https url is recognized`() {
        assertTrue(isBareUrlOrEmail("https://swiss.com/dangerousgoods"))
    }

    @Test fun `a bare email address is recognized`() {
        assertTrue(isBareUrlOrEmail("support@swiss.com"))
    }

    @Test fun `surrounding whitespace is trimmed before matching`() {
        assertTrue(isBareUrlOrEmail("  https://swiss.com  \n"))
    }

    @Test fun `free text that merely contains a url is not treated as a bare link`() {
        assertFalse(isBareUrlOrEmail("More information: https://swiss.com/dangerousgoods"))
    }

    @Test fun `free text with embedded newlines is not treated as a bare link`() {
        assertFalse(isBareUrlOrEmail("Status\nADOK\n\nTravel class\nEconomy"))
    }

    @Test fun `blank value is not a bare link`() {
        assertFalse(isBareUrlOrEmail(""))
        assertFalse(isBareUrlOrEmail("   "))
    }

    @Test fun `an oversized value is rejected before reaching the regex engine`() {
        // Guards against Patterns.WEB_URL's known catastrophic-backtracking ANR risk on
        // adversarial, issuer-supplied input — a real bare URL/email is never this long.
        val huge = "a".repeat(10_000)
        assertFalse(isBareUrlOrEmail(huge))
    }
}
