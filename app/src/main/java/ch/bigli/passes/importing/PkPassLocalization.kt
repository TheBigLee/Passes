package ch.bigli.passes.importing

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Resolves Apple pkpass localization — `<lang>.lproj/pass.strings` translation tables and
 * per-language image overrides — against a target language, matched live at read time (not
 * baked in at import time) so a device language change takes effect on the next read without
 * needing to re-import the pass.
 *
 * Matching is language-only (e.g. device `de-CH` matches a `de.lproj` folder; there's no
 * separate region-specific preference) since real-world pkpass files essentially never ship
 * region-specific folders.
 */
class PkPassLocalization private constructor(
    private val translations: Map<String, String>,
    private val entryNames: Set<String>,
    val folder: String?,
) {
    /** Looks up [text] verbatim in the matched folder's translation table; falls back to [text] unchanged if absent (or if there's no matched folder), and passes null through as null. */
    fun translate(text: String?): String? = text?.let { translations[it] ?: it }

    /** Best `@3x`/`@2x`/base resolution for [baseName] inside the matched folder, or null if there's no folder or no override for it. */
    fun imageEntryName(baseName: String): String? {
        val f = folder ?: return null
        return listOf("$baseName@3x.png", "$baseName@2x.png", "$baseName.png")
            .map { "$f/$it" }
            .firstOrNull { it in entryNames }
    }

    companion object {
        private val LPROJ_ENTRY = Regex("""^([^/]+\.lproj)/.+$""")

        /** Builds a [PkPassLocalization] for a pkpass zip's raw [zipBytes], matched against [languageTag] (defaults to the device's current language). */
        fun forZip(zipBytes: ByteArray, languageTag: String = Locale.getDefault().language): PkPassLocalization {
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                    entry = zip.nextEntry
                }
            }
            val expectedFolder = "$languageTag.lproj"
            val folder = entries.keys
                .mapNotNull { LPROJ_ENTRY.find(it)?.groupValues?.get(1) }
                .distinct()
                .firstOrNull { it.equals(expectedFolder, ignoreCase = true) }
            val translations = folder
                ?.let { entries["$it/pass.strings"] }
                ?.let { parseStrings(it) }
                .orEmpty()
            return PkPassLocalization(translations, entries.keys, folder)
        }

        /** Parses Apple's `.strings` format: `"KEY" = "VALUE";` entries, `//`/`/* */` comments, `\"`/`\\` escapes within quoted strings. */
        internal fun parseStrings(bytes: ByteArray): Map<String, String> {
            val text = decodeStringsBytes(bytes)
            val result = mutableMapOf<String, String>()
            var i = 0
            val n = text.length

            fun skipWhitespaceAndComments() {
                while (i < n) {
                    val c = text[i]
                    if (c.isWhitespace()) { i++; continue }
                    if (c == '/' && i + 1 < n && text[i + 1] == '/') {
                        i += 2
                        while (i < n && text[i] != '\n') i++
                        continue
                    }
                    if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                        i += 2
                        while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                        i = (i + 2).coerceAtMost(n)
                        continue
                    }
                    break
                }
            }

            fun readQuotedString(): String? {
                if (i >= n || text[i] != '"') return null
                i++
                val sb = StringBuilder()
                while (i < n && text[i] != '"') {
                    if (text[i] == '\\' && i + 1 < n) {
                        sb.append(text[i + 1])
                        i += 2
                    } else {
                        sb.append(text[i])
                        i++
                    }
                }
                if (i < n) i++ // closing quote
                return sb.toString()
            }

            while (true) {
                skipWhitespaceAndComments()
                if (i >= n) break
                val key = readQuotedString() ?: break
                skipWhitespaceAndComments()
                if (i >= n || text[i] != '=') break
                i++
                skipWhitespaceAndComments()
                val value = readQuotedString() ?: break
                result[key] = value
                skipWhitespaceAndComments()
                if (i < n && text[i] == ';') i++
            }
            return result
        }

        private fun decodeStringsBytes(bytes: ByteArray): String = when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            else -> bytes.toString(Charsets.UTF_8)
        }
    }
}
