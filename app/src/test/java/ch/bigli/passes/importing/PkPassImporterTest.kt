package ch.bigli.passes.importing

import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.PassType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PkPassImporterTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")) { "missing $name" }

    private val importer = PkPassImporter()

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
}
