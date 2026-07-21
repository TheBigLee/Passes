package ch.bigli.passes.barcode

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import ch.bigli.passes.domain.Barcode
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX analyzer that decodes the first barcode it sees from the frame's luminance (Y) plane,
 * then calls [onResult] exactly once. Set as the analyzer with a main-thread executor so [onResult]
 * is delivered on the main thread.
 */
class CameraBarcodeAnalyzer(private val onResult: (Barcode) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply { setHints(zxingHints()) }
    private val done = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (done.get()) { image.close(); return }
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val source = PlanarYUVLuminanceSource(
                data, plane.rowStride, image.height,
                0, 0, image.width, image.height, false,
            )
            val result = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            } catch (e: Exception) {
                null
            } finally {
                reader.reset()
            }
            val format = result?.barcodeFormat?.toDomain()
            if (result != null && format != null && done.compareAndSet(false, true)) {
                onResult(Barcode(format, result.text, null))
            }
        } finally {
            image.close()
        }
    }
}
