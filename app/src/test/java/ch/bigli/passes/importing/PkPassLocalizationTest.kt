package ch.bigli.passes.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PkPassLocalizationTest {
    private fun buildZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun `matches the lproj folder for the given language, case-insensitively`() {
        val zip = buildZip(mapOf(
            "pass.json" to "{}".toByteArray(),
            "de.lproj/pass.strings" to "\"Gate\" = \"Tor\";".toByteArray(),
            "fr.lproj/pass.strings" to "\"Gate\" = \"Porte\";".toByteArray(),
        ))
        val localization = PkPassLocalization.forZip(zip, "DE")
        assertEquals("de.lproj", localization.folder)
        assertEquals("Tor", localization.translate("Gate"))
    }

    @Test fun `no matching folder leaves text untranslated and folder null`() {
        val zip = buildZip(mapOf(
            "pass.json" to "{}".toByteArray(),
            "fr.lproj/pass.strings" to "\"Gate\" = \"Porte\";".toByteArray(),
        ))
        val localization = PkPassLocalization.forZip(zip, "de")
        assertNull(localization.folder)
        assertEquals("Gate", localization.translate("Gate"))
    }

    @Test fun `a matched folder's untranslated string falls back to the original`() {
        val zip = buildZip(mapOf("de.lproj/pass.strings" to "\"Gate\" = \"Tor\";".toByteArray()))
        val localization = PkPassLocalization.forZip(zip, "de")
        assertEquals("Seat 4B", localization.translate("Seat 4B"))
    }

    @Test fun `translate passes null through unchanged`() {
        val localization = PkPassLocalization.forZip(buildZip(emptyMap()), "de")
        assertNull(localization.translate(null))
    }

    @Test fun `parses strings with line and block comments and escaped quotes`() {
        val strings = """
            // a leading comment
            "Gate" = "Tor"; /* trailing block comment */
            "Say \"Hi\"" = "Sag \"Hallo\"";
        """.trimIndent()
        val zip = buildZip(mapOf("de.lproj/pass.strings" to strings.toByteArray()))
        val localization = PkPassLocalization.forZip(zip, "de")
        assertEquals("Tor", localization.translate("Gate"))
        assertEquals("Sag \"Hallo\"", localization.translate("Say \"Hi\""))
    }

    @Test fun `parses UTF-16 with BOM strings files`() {
        val content = "\"Gate\" = \"Tor\";"
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + content.toByteArray(Charsets.UTF_16BE)
        val zip = buildZip(mapOf("de.lproj/pass.strings" to bytes))
        val localization = PkPassLocalization.forZip(zip, "de")
        assertEquals("Tor", localization.translate("Gate"))
    }

    @Test fun `imageEntryName prefers 3x then 2x then base within the matched folder`() {
        val zip = buildZip(mapOf(
            "de.lproj/logo@2x.png" to byteArrayOf(1),
            "de.lproj/logo.png" to byteArrayOf(2),
            "logo@3x.png" to byteArrayOf(3),
        ))
        val localization = PkPassLocalization.forZip(zip, "de")
        assertEquals("de.lproj/logo@2x.png", localization.imageEntryName("logo"))
        assertNull(localization.imageEntryName("strip"))
    }

    @Test fun `imageEntryName is null when there is no matched folder`() {
        val localization = PkPassLocalization.forZip(buildZip(emptyMap()), "de")
        assertNull(localization.imageEntryName("logo"))
    }
}
