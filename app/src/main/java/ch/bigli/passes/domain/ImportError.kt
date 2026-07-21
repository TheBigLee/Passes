package ch.bigli.passes.domain

sealed class ImportError(message: String) : Exception(message) {
    class UnsupportedFormat(detail: String) : ImportError("Unsupported format: $detail")
    class CorruptFile(detail: String) : ImportError("Corrupt file: $detail")
    class NoBarcode(detail: String) : ImportError("No barcode found: $detail")
}
