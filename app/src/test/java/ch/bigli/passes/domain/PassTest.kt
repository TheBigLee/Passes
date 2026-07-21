package ch.bigli.passes.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassTest {
    @Test fun `pass exposes primary field values`() {
        val pass = Pass(
            id = "abc",
            type = PassType.BOARDING,
            title = "ZRH → JFK",
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
}
