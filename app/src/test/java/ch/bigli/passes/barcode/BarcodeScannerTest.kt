package ch.bigli.passes.barcode

import android.graphics.Bitmap
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BarcodeScannerTest {
    private val renderer = BarcodeRenderer()
    private val scanner = BarcodeScanner()

    @Test fun `round-trips a QR code`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.QR, "HELLO", null), 400, 400)
        val found = scanner.scan(bmp)
        assertNotNull(found)
        assertEquals(BarcodeFormat.QR, found!!.format)
        assertEquals("HELLO", found.message)
    }

    @Test fun `round-trips a code128`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.CODE128, "12345678", null), 600, 300)
        val found = scanner.scan(bmp)
        assertNotNull(found)
        assertEquals(BarcodeFormat.CODE128, found!!.format)
        assertEquals("12345678", found.message)
    }

    @Test fun `returns null for a blank image`() {
        val blank = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        assertNull(scanner.scan(blank))
    }
}
