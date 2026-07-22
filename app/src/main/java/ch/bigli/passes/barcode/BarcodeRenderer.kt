package ch.bigli.passes.barcode

import android.graphics.Bitmap
import android.graphics.Color
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import com.google.zxing.BarcodeFormat as ZxFormat
import com.google.zxing.MultiFormatWriter

class BarcodeRenderer {
    fun render(barcode: Barcode, width: Int, height: Int): Bitmap {
        val zxFormat = when (barcode.format) {
            BarcodeFormat.QR -> ZxFormat.QR_CODE
            BarcodeFormat.PDF417 -> ZxFormat.PDF_417
            BarcodeFormat.AZTEC -> ZxFormat.AZTEC
            BarcodeFormat.CODE128 -> ZxFormat.CODE_128
        }
        // width/height are hints to the writer; the returned matrix's own dimensions are the
        // source of truth. For PDF417/Aztec the writer returns a matrix sized to the barcode's
        // natural aspect ratio, NOT the requested size — iterating the requested bounds would
        // read past the matrix and throw ArrayIndexOutOfBoundsException.
        val matrix = MultiFormatWriter().encode(barcode.message, zxFormat, width, height)
        // ZXing's own quiet-zone padding around the encoded content isn't always symmetric
        // (depends on how evenly the requested size divides into whole modules), which is
        // invisible against a white background but makes the visible pattern look off-center.
        // Trim to the actual content's bounding box, then add back a small uniform quiet zone.
        val enclosing = matrix.enclosingRectangle
        val contentLeft = enclosing?.get(0) ?: 0
        val contentTop = enclosing?.get(1) ?: 0
        val contentWidth = enclosing?.get(2) ?: matrix.width
        val contentHeight = enclosing?.get(3) ?: matrix.height
        val quietZone = (maxOf(contentWidth, contentHeight) * 0.04f).toInt().coerceAtLeast(4)
        val cropLeft = (contentLeft - quietZone).coerceAtLeast(0)
        val cropTop = (contentTop - quietZone).coerceAtLeast(0)
        val cropRight = (contentLeft + contentWidth + quietZone).coerceAtMost(matrix.width)
        val cropBottom = (contentTop + contentHeight + quietZone).coerceAtMost(matrix.height)
        val w = cropRight - cropLeft
        val h = cropBottom - cropTop
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(x, y, if (matrix[cropLeft + x, cropTop + y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
