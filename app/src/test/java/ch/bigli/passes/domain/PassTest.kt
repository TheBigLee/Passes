package ch.bigli.passes.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassTest {
    @Test fun `pass exposes primary field values`() {
        val pass = Pass(
            id = "abc",
            type = PassType.BOARDING,
            subtitle = "SWISS",
            organization = "SWISS",
            bgColor = 0xFF1A73E8,
            fgColor = 0xFFFFFFFF,
            fields = listOf(PassField("GATE", "A12", FieldPosition.PRIMARY)),
            barcode = Barcode(BarcodeFormat.QR, "M1SWISS", "M1SWISS"),
            relevantDate = null,
            rawFilePath = "/data/x.pkpass",
            sourceFormat = SourceFormat.PKPASS,
            updateInfo = null,
        )
        assertEquals("A12", pass.fields.first { it.position == FieldPosition.PRIMARY }.value)
        assertTrue(pass.barcode != null)
    }

    @Test fun `backFields defaults to empty`() {
        val pass = Pass(
            id = "abc",
            type = PassType.GENERIC,
            subtitle = null,
            organization = null,
            bgColor = null,
            fgColor = null,
            fields = emptyList(),
            barcode = null,
            relevantDate = null,
            rawFilePath = "/data/x.pkpass",
            sourceFormat = SourceFormat.MANUAL,
            updateInfo = null,
        )
        assertEquals(emptyList<PassField>(), pass.backFields)
    }

    @Test fun `backFields round-trips through copy`() {
        val base = Pass(
            id = "abc",
            type = PassType.BOARDING,
            subtitle = null,
            organization = null,
            bgColor = null,
            fgColor = null,
            fields = emptyList(),
            barcode = null,
            relevantDate = null,
            rawFilePath = "/data/x.pkpass",
            sourceFormat = SourceFormat.PKPASS,
            updateInfo = null,
        )
        val back = listOf(PassField("Terms", "Non-refundable", FieldPosition.BACK))
        val pass = base.copy(backFields = back)
        assertEquals(back, pass.backFields)
    }
}
