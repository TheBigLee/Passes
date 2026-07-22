package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class PassRepositoryRefreshTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository
    private lateinit var server: TestHttpServer
    private lateinit var base: String

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
        server = TestHttpServer()
        server.start()
        base = "http://127.0.0.1:${server.port}"
    }

    @After fun tearDown() { server.close(); db.close() }

    /**
     * Builds a minimal valid pkpass zip (just pass.json — PkPassImporter doesn't require images)
     * with a webServiceURL/authToken pointing at the given base, so refreshPass has something to
     * poll. The test server's port is random per run, so this can't be a static test resource.
     */
    private fun buildPkPass(
        webServiceUrl: String,
        organizationName: String = "Acme",
        serial: String = "SN1",
        voided: Boolean = false,
    ): ByteArray {
        val json = """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.test",
              "serialNumber": "$serial",
              "teamIdentifier": "TEAM",
              "organizationName": "$organizationName",
              "description": "Test pass",
              "webServiceURL": "$webServiceUrl",
              "authenticationToken": "tok-123",
              "voided": $voided,
              "generic": { "primaryFields": [] }
            }
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pass.json"))
            zip.write(json.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private val path = "/v1/passes/pass.test/SN1"

    @Test fun `refreshPass on 200 replaces fields but preserves id and title, stores Last-Modified`() = runTest {
        val imported = repo.import(buildPkPass(base), "test.pkpass")
        repo.updateTitle(imported.id, "My Custom Title")

        server.respond(path) {
            TestHttpServer.Response(
                200, buildPkPass(base, organizationName = "Acme Updated"),
                headers = mapOf("Last-Modified" to "Wed, 21 Oct 2026 07:28:00 GMT"),
            )
        }

        val result = repo.refreshPass(imported.id)
        check(result is RefreshResult.Updated)
        assertEquals(imported.id, result.pass.id)
        assertEquals("My Custom Title", result.pass.title)
        assertEquals("Acme Updated", result.pass.organization)
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", result.pass.lastModified)

        val stored = repo.getById(imported.id)!!
        assertEquals("My Custom Title", stored.title)
        assertEquals("Acme Updated", stored.organization)
    }

    @Test fun `refreshPass sends If-Modified-Since from a prior fetch and honors 304`() = runTest {
        val imported = repo.import(buildPkPass(base), "test.pkpass")
        server.respond(path) {
            TestHttpServer.Response(200, buildPkPass(base), headers = mapOf("Last-Modified" to "Mon, 01 Jan 2026 00:00:00 GMT"))
        }
        repo.refreshPass(imported.id) // first fetch: populates lastModified

        server.respond(path) { headers ->
            assertEquals("Mon, 01 Jan 2026 00:00:00 GMT", headers["If-Modified-Since"])
            TestHttpServer.Response(304, ByteArray(0))
        }
        val result = repo.refreshPass(imported.id)
        assertEquals(RefreshResult.Unchanged, result)
        assertEquals("Acme", repo.getById(imported.id)!!.organization)
    }

    @Test fun `refreshPass on 410 marks the pass voided and stops further polling`() = runTest {
        val imported = repo.import(buildPkPass(base), "test.pkpass")
        server.respond(path, 410, ByteArray(0))

        val result = repo.refreshPass(imported.id)
        assertEquals(RefreshResult.Voided, result)
        assertTrue(repo.getById(imported.id)!!.voided)

        val second = repo.refreshPass(imported.id)
        assertEquals(RefreshResult.NotUpdatable, second)
    }

    @Test fun `refreshPass on a malformed 200 body leaves the stored pass untouched`() = runTest {
        val imported = repo.import(buildPkPass(base), "test.pkpass")
        server.respond(path, 200, "not a zip".toByteArray())

        val result = repo.refreshPass(imported.id)
        assertTrue(result is RefreshResult.Error)
        assertEquals("Acme", repo.getById(imported.id)!!.organization)
    }

    @Test fun `refreshPass on 200 does not silently clear voided when the fresh pass json declares it`() = runTest {
        // Imported while not yet voided; the issuer later republishes pass.json with voided: true
        // (a static declaration, not a 410) and a successful 200 refresh must adopt that, not
        // force it back to false.
        val imported = repo.import(buildPkPass(base, voided = false), "test.pkpass")
        assertFalse(repo.getById(imported.id)!!.voided)

        server.respond(path) {
            TestHttpServer.Response(200, buildPkPass(base, voided = true))
        }

        val result = repo.refreshPass(imported.id)
        check(result is RefreshResult.Updated)
        assertTrue(result.pass.voided)
        assertTrue(repo.getById(imported.id)!!.voided)
    }

    @Test fun `refreshPass on a pass without updateInfo returns NotUpdatable without a network call`() = runTest {
        val manual = repo.createManualPass("Coop card", BarcodeFormat.CODE128, "6001234567890")
        val result = repo.refreshPass(manual.id)
        assertEquals(RefreshResult.NotUpdatable, result)
    }
}
