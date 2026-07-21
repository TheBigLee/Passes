package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PassRepositoryTest {
    private lateinit var db: PassDatabase
    private lateinit var ctx: Context
    private lateinit var repo: PassRepository

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
    }

    @After fun tearDown() = db.close()

    @Test fun `importing a pkpass persists it and copies the raw file`() = runTest {
        val pass = repo.import(fixture("sample.pkpass"), "sample.pkpass")
        assertEquals("SWISS", pass.organization)
        assertTrue(File(pass.rawFilePath).exists())
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test fun `importing an unknown format throws UnsupportedFormat`() = runTest {
        var thrown: Throwable? = null
        try {
            repo.import("hello world".toByteArray(), "note.txt")
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is ImportError.UnsupportedFormat)
    }
}
