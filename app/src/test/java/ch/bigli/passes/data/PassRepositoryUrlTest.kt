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

@RunWith(RobolectricTestRunner::class)
class PassRepositoryUrlTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository
    private lateinit var server: TestHttpServer
    private lateinit var base: String

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
        val bytes = fixture("sample.pkpass")
        server = TestHttpServer()
        server.respond("/sample.pkpass", 200, bytes)
        server.respond("/missing.pkpass", 404, ByteArray(0))
        server.start()
        base = "http://127.0.0.1:${server.port}"
    }

    @After fun tearDown() { server.close(); db.close() }

    @Test fun `importFromUrl downloads and persists a pass`() = runTest {
        val pass = repo.importFromUrl("$base/sample.pkpass")
        assertEquals("SWISS", pass.organization)
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test fun `importFromUrl throws on http error`() = runTest {
        try {
            repo.importFromUrl("$base/missing.pkpass")
            error("expected ImportError")
        } catch (e: Exception) {
            assertTrue(e is ImportError)
        }
    }
}
