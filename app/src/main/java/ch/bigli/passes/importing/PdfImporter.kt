package ch.bigli.passes.importing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import ch.bigli.passes.barcode.BarcodeScanner
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import java.io.File
import java.util.UUID

/** Imports a PDF ticket by rendering its pages and detecting the embedded barcode. */
class PdfImporter(private val scanner: BarcodeScanner = BarcodeScanner()) : PassImporter {

    override fun import(bytes: ByteArray, rawFilePath: String, displayName: String): Pass {
        val barcode = extractBarcode(File(rawFilePath)) ?: throw ImportError.NoBarcode(displayName)
        val title = displayName
            .removeSuffix(".pdf").removeSuffix(".PDF")
            .ifBlank { "PDF ticket" }
        return Pass(
            id = UUID.randomUUID().toString(),
            type = PassType.GENERIC,
            title = title,
            subtitle = null,
            organization = null,
            bgColor = null,
            fgColor = null,
            fields = emptyList(),
            barcode = barcode,
            relevantDate = null,
            rawFilePath = rawFilePath,
            sourceFormat = SourceFormat.PDF,
            updateInfo = null,
        )
    }

    /** Renders each page and returns the first detected barcode, or null if none. */
    private fun extractBarcode(file: File): Barcode? {
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            throw ImportError.CorruptFile("cannot open PDF: ${e.message}")
        }
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: Exception) {
            pfd.close()
            throw ImportError.CorruptFile("not a valid PDF: ${e.message}")
        }
        try {
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    val bmp = renderPage(page)
                    val found = scanner.scan(bmp)
                    if (found != null) return found
                }
            }
            return null
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val scale = 2
        var w = page.width * scale
        var h = page.height * scale
        val longest = maxOf(w, h)
        if (longest > MAX_DIMENSION) {
            val factor = MAX_DIMENSION.toDouble() / longest
            w = (w * factor).toInt()
            h = (h * factor).toInt()
        }
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE) // PDFs assume a white background; transparent breaks detection
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }

    private companion object {
        const val MAX_DIMENSION = 2000
    }
}
