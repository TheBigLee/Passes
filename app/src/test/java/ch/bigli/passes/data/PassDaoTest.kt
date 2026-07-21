package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassDaoTest {
    private lateinit var db: PassDatabase
    private lateinit var dao: PassDao

    private fun sample(id: String) = Pass(
        id = id, type = PassType.EVENT, title = "Concert", subtitle = "Venue",
        organization = "Org", bgColor = 0xFF34A853, fgColor = 0xFFFFFFFF,
        fields = listOf(PassField("WHEN", "21:00", FieldPosition.PRIMARY)),
        barcode = Barcode(BarcodeFormat.QR, "XYZ", "XYZ"),
        relevantDate = null, rawFilePath = "/data/$id.pkpass",
        sourceFormat = SourceFormat.PKPASS, updateInfo = null,
    )

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        dao = db.passDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `insert then observe returns the pass`() = runTest {
        dao.insert(sample("a").toEntity())
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        val roundTripped = all.first().toDomain()
        assertEquals("Concert", roundTripped.title)
        assertEquals(BarcodeFormat.QR, roundTripped.barcode!!.format)
        assertEquals("21:00", roundTripped.fields.first().value)
    }

    @Test fun `getById then delete removes it`() = runTest {
        dao.insert(sample("b").toEntity())
        assertEquals("Concert", dao.getById("b")!!.toDomain().title)
        dao.deleteById("b")
        assertNull(dao.getById("b"))
    }
}
