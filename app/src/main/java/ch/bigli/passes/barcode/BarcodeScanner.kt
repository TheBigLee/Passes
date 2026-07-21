package ch.bigli.passes.barcode

import android.graphics.Bitmap
import ch.bigli.passes.domain.Barcode
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Detects a supported barcode inside a bitmap (e.g. a rendered PDF page) using ZXing. */
class BarcodeScanner {
    fun scan(bitmap: Bitmap): Barcode? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val result = try {
            MultiFormatReader().decode(binary, zxingHints())
        } catch (e: Exception) {
            return null
        }
        val format = result.barcodeFormat.toDomain() ?: return null
        return Barcode(format, result.text, null)
    }
}
