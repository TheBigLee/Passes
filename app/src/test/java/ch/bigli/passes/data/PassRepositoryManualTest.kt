package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import ch.bigli.passes.domain.TransitType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PassRepositoryManualTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao())
    }

    @After fun tearDown() = db.close()

    @Test fun `createManualPass stores a GENERIC pass with no extra fields`() = runTest {
        val pass = repo.createManualPass(
            type = PassType.GENERIC,
            organization = "Acme",
            fields = emptyList(),
            relevantDate = null,
            transitType = null,
            barcodeFormat = BarcodeFormat.CODE128,
            barcodeValue = "6001234567890",
        )
        val stored = repo.getById(pass.id)!!
        assertEquals(PassType.GENERIC, stored.type)
        assertEquals(SourceFormat.MANUAL, stored.sourceFormat)
        assertEquals("Acme", stored.organization)
        assertEquals("Acme", stored.subtitle)
        assertEquals(BarcodeFormat.CODE128, stored.barcode!!.format)
        assertEquals("6001234567890", stored.barcode!!.message)
        assertEquals("", stored.rawFilePath)
        assertNull(stored.transitType)
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test fun `createManualPass stores BOARDING fields, relevantDate, and transitType`() = runTest {
        val fields = listOf(
            PassField("Departure", "Zurich", FieldPosition.PRIMARY),
            PassField("Arrival", "Warsaw", FieldPosition.PRIMARY),
        )
        val relevantDate = Instant.parse("2026-08-15T08:45:00Z")
        val pass = repo.createManualPass(
            type = PassType.BOARDING,
            organization = "SWISS",
            fields = fields,
            relevantDate = relevantDate,
            transitType = TransitType.AIR,
            barcodeFormat = BarcodeFormat.QR,
            barcodeValue = "SWISS123",
        )
        val stored = repo.getById(pass.id)!!
        assertEquals(PassType.BOARDING, stored.type)
        assertEquals(fields, stored.fields)
        assertEquals(relevantDate, stored.relevantDate)
        assertEquals(TransitType.AIR, stored.transitType)
    }
}
