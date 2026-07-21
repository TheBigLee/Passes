package ch.bigli.passes.data

import ch.bigli.passes.domain.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassFormatDetectorTest {
    @Test fun `detects a zip (pkpass) by PK magic`() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)
        assertEquals(SourceFormat.PKPASS, detectPassFormat(zip))
    }

    @Test fun `detects a pdf by percent-PDF magic`() {
        val pdf = "%PDF-1.7\n...".toByteArray()
        assertEquals(SourceFormat.PDF, detectPassFormat(pdf))
    }

    @Test fun `returns null for unknown content`() {
        assertNull(detectPassFormat("hello world".toByteArray()))
    }

    @Test fun `returns null for too-short input`() {
        assertNull(detectPassFormat(byteArrayOf(0x25)))
    }
}
