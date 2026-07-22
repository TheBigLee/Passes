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

    @Test fun `computeTitle prefers two primary field labels, joined by an arrow`() {
        val fields = listOf(
            PassField("ZRH", "Zurich", FieldPosition.PRIMARY),
            PassField("JFK", "New York", FieldPosition.PRIMARY),
        )
        assertEquals("ZRH → JFK", computeTitle(fields, description = "Boarding pass", organizationName = "SWISS"))
    }

    @Test fun `computeTitle uses the single primary field's value when there is only one`() {
        val fields = listOf(PassField("Type", "VIP", FieldPosition.PRIMARY))
        assertEquals("VIP", computeTitle(fields, description = "Event ticket", organizationName = "Acme"))
    }

    @Test fun `computeTitle falls back to description then organization then a default when there are no primary fields`() {
        assertEquals("Some description", computeTitle(emptyList(), description = "Some description", organizationName = "Acme"))
        assertEquals("Acme", computeTitle(emptyList(), description = null, organizationName = "Acme"))
        assertEquals("Pass", computeTitle(emptyList(), description = null, organizationName = null))
    }
}
