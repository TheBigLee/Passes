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
        val w = matrix.width
        val h = matrix.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
