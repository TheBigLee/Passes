package ch.bigli.passes.barcode

import android.graphics.Bitmap
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import com.google.zxing.BarcodeFormat as ZxFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
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
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                ZxFormat.QR_CODE, ZxFormat.PDF_417, ZxFormat.AZTEC, ZxFormat.CODE_128,
            ),
            DecodeHintType.TRY_HARDER to true,
        )
        val result = try {
            MultiFormatReader().decode(binary, hints)
        } catch (e: Exception) {
            return null
        }
        val format = when (result.barcodeFormat) {
            ZxFormat.QR_CODE -> BarcodeFormat.QR
            ZxFormat.PDF_417 -> BarcodeFormat.PDF417
            ZxFormat.AZTEC -> BarcodeFormat.AZTEC
            ZxFormat.CODE_128 -> BarcodeFormat.CODE128
            else -> return null
        }
        return Barcode(format, result.text, null)
    }
}
