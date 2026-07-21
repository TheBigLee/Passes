package ch.bigli.passes.importing

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletPassesLinkTest {
    @Test fun `decodes the encoded https target after import host`() {
        val uri = Uri.parse("walletpasses://import/https%3A%2F%2Fwalletpasses.io%2Fsample.pkpass")
        assertEquals("https://walletpasses.io/sample.pkpass", walletPassesTargetUrl(uri))
    }

    @Test fun `accepts http as well as https`() {
        val uri = Uri.parse("walletpasses://import/http%3A%2F%2Fexample.com%2Fp.pkpass")
        assertEquals("http://example.com/p.pkpass", walletPassesTargetUrl(uri))
    }

    @Test fun `rejects a non-http decoded target`() {
        val uri = Uri.parse("walletpasses://import/file%3A%2F%2F%2Fetc%2Fpasswd")
        assertNull(walletPassesTargetUrl(uri))
    }

    @Test fun `returns null when there is no path`() {
        assertNull(walletPassesTargetUrl(Uri.parse("walletpasses://import")))
    }
}
