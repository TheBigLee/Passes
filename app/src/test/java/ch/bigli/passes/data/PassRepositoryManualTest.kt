package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    @Test fun `createManualPass stores a MANUAL generic pass`() = runTest {
        val pass = repo.createManualPass("Coop card", BarcodeFormat.CODE128, "6001234567890")
        val stored = repo.getById(pass.id)!!
        assertEquals("Coop card", stored.title)
        assertEquals(PassType.GENERIC, stored.type)
        assertEquals(SourceFormat.MANUAL, stored.sourceFormat)
        assertEquals(BarcodeFormat.CODE128, stored.barcode!!.format)
        assertEquals("6001234567890", stored.barcode!!.message)
        assertEquals("", stored.rawFilePath)
        assertEquals(1, repo.observeAll().first().size)
    }
}
