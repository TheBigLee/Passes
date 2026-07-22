package ch.bigli.passes.importing

import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.PassType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PkPassImporterTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")) { "missing $name" }

    private val importer = PkPassImporter()

    /** Builds a minimal valid pkpass zip (just pass.json) with the given raw pass.json body. */
    private fun buildPkPass(passJson: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pass.json"))
            zip.write(passJson.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test fun `parses boarding pass core fields`() {
        val pass = importer.import(fixture("sample.pkpass").readBytes(), "/data/sample.pkpass", "sample.pkpass")
        assertEquals(PassType.BOARDING, pass.type)
        assertEquals("SWISS", pass.organization)
        assertEquals(0xFF1A73E8, pass.bgColor)
        assertEquals(BarcodeFormat.QR, pass.barcode!!.format)
        assertEquals("M1SWISS ZRHJFK", pass.barcode!!.message)
        assertEquals(2, pass.fields.count { it.position == FieldPosition.PRIMARY })
        assertEquals("A12", pass.fields.first { it.label == "GATE" }.value)
    }

    @Test fun `rejects non-zip file`() {
        assertThrows(ImportError.CorruptFile::class.java) {
            importer.import(fixture("notazip.pkpass").readBytes(), "/data/x.pkpass", "x.pkpass")
        }
    }

    @Test fun `rejects zip without pass json`() {
        assertThrows(ImportError.CorruptFile::class.java) {
            importer.import(fixture("nopassjson.pkpass").readBytes(), "/data/x.pkpass", "x.pkpass")
        }
    }

    @Test fun `parses voided and expirationDate from pass json`() {
        val passJson = """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.test",
              "serialNumber": "SN1",
              "teamIdentifier": "TEAM",
              "organizationName": "Acme",
              "description": "Test pass",
              "voided": true,
              "expirationDate": "2021-10-09T18:00:42+01:00",
              "generic": { "primaryFields": [] }
            }
        """.trimIndent()
        val pass = importer.import(buildPkPass(passJson), "/data/x.pkpass", "x.pkpass")
        assertTrue(pass.voided)
        assertEquals(Instant.parse("2021-10-09T18:00:42+01:00"), pass.expirationDate)
    }

    @Test fun `defaults voided to false and expirationDate to null when absent`() {
        val passJson = """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.test",
              "serialNumber": "SN1",
              "teamIdentifier": "TEAM",
              "organizationName": "Acme",
              "description": "Test pass",
              "generic": { "primaryFields": [] }
            }
        """.trimIndent()
        val pass = importer.import(buildPkPass(passJson), "/data/x.pkpass", "x.pkpass")
        assertEquals(false, pass.voided)
        assertEquals(null, pass.expirationDate)
    }
}
