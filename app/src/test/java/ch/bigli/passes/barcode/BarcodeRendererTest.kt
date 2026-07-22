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

    // Trimmed to content + a small quiet zone (see BarcodeRenderer), so the bitmap is no longer
    // exactly the requested size - just square and no bigger than what was requested.
    @Test fun `renders qr to a square bitmap no larger than the requested size`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.QR, "HELLO", null), 300, 300)
        assertNotNull(bmp)
        assertEquals(bmp.width, bmp.height)
        assertTrue(bmp.width in 1..300)
    }

    @Test fun `renders code128 as a wide bitmap no larger than the requested size`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.CODE128, "12345678", null), 600, 200)
        assertTrue(bmp.width > bmp.height)
        assertTrue(bmp.width in 1..600)
        assertTrue(bmp.height in 1..200)
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

    // Regression: ZXing's own quiet-zone padding around the encoded content isn't always
    // symmetric (depends on how evenly the requested size divides into whole modules), which
    // was invisible against a white background but made the pattern look off-center once
    // displayed fullscreen. Verifies the actual black content - not just the bitmap's own
    // dimensions - sits centered within the returned bitmap.
    @Test fun `qr content is centered within the trimmed bitmap, not just its own quiet zone`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.QR, "HELLO", null), 300, 300)
        var minX = bmp.width
        var maxX = -1
        var minY = bmp.height
        var maxY = -1
        for (x in 0 until bmp.width) {
            for (y in 0 until bmp.height) {
                if (bmp.getPixel(x, y) == android.graphics.Color.BLACK) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        val leftMargin = minX
        val rightMargin = bmp.width - 1 - maxX
        val topMargin = minY
        val bottomMargin = bmp.height - 1 - maxY
        assertTrue("left=$leftMargin right=$rightMargin", kotlin.math.abs(leftMargin - rightMargin) <= 3)
        assertTrue("top=$topMargin bottom=$bottomMargin", kotlin.math.abs(topMargin - bottomMargin) <= 3)
    }
}
