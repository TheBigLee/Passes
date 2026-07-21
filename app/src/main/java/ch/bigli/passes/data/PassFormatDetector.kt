package ch.bigli.passes.data

import ch.bigli.passes.domain.SourceFormat

/**
 * Sniffs the importable format from a file's leading magic bytes.
 * `PK` (0x50 0x4B) → a zip, i.e. a `.pkpass`; `%PDF` → a PDF. Null if neither.
 */
fun detectPassFormat(bytes: ByteArray): SourceFormat? = when {
    bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() ->
        SourceFormat.PKPASS
    bytes.size >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() ->
        SourceFormat.PDF
    else -> null
}
