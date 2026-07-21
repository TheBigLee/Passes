package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassRepositoryUpdateTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
    }

    @After fun tearDown() = db.close()

    @Test fun `updateTitle changes the stored title`() = runTest {
        val pass = repo.import(fixture("sample.pkpass"), "sample.pkpass")
        repo.updateTitle(pass.id, "My Trip")
        assertEquals("My Trip", repo.getById(pass.id)!!.title)
    }

    @Test fun `updateTitle ignores a blank title`() = runTest {
        val pass = repo.import(fixture("sample.pkpass"), "sample.pkpass")
        repo.updateTitle(pass.id, "My Trip")
        repo.updateTitle(pass.id, "   ")
        assertEquals("My Trip", repo.getById(pass.id)!!.title)
    }
}
