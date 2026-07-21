package ch.bigli.passes.barcode

import ch.bigli.passes.domain.BarcodeFormat
import com.google.zxing.BarcodeFormat as ZxFormat
import com.google.zxing.DecodeHintType

/** ZXing decode hints shared by the still-image and camera scanners. */
internal fun zxingHints(): Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(
        ZxFormat.QR_CODE, ZxFormat.PDF_417, ZxFormat.AZTEC, ZxFormat.CODE_128,
    ),
    DecodeHintType.TRY_HARDER to true,
)

/** Maps a ZXing format to our domain format, or null if unsupported. */
internal fun ZxFormat.toDomain(): BarcodeFormat? = when (this) {
    ZxFormat.QR_CODE -> BarcodeFormat.QR
    ZxFormat.PDF_417 -> BarcodeFormat.PDF417
    ZxFormat.AZTEC -> BarcodeFormat.AZTEC
    ZxFormat.CODE_128 -> BarcodeFormat.CODE128
    else -> null
}
