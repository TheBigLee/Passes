package ch.bigli.passes.barcode

import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BarcodeRendererTest {
    private val renderer = BarcodeRenderer()

    @Test fun `renders qr to square bitmap of requested size`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.QR, "HELLO", null), 300, 300)
        assertNotNull(bmp)
        assertEquals(300, bmp.width)
        assertEquals(300, bmp.height)
    }

    @Test fun `renders code128 as wide bitmap`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.CODE128, "12345678", null), 600, 200)
        assertEquals(600, bmp.width)
        assertEquals(200, bmp.height)
    }

    // Regression: ZXing's PDF417 writer returns a BitMatrix whose dimensions differ from the
    // requested width/height, so iterating the requested bounds threw ArrayIndexOutOfBounds
    // and crashed the detail screen for real store-card passes.
    @Test fun `renders pdf417 without going out of bounds`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.PDF417, "gt4SSz3Rp891mA", null), 800, 300)
        assertNotNull(bmp)
        assertTrue(bmp.width > 0)
        assertTrue(bmp.height > 0)
    }
}
