package ch.bigli.passes.barcode

import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
