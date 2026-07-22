package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.flow.first
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
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class PassRepositoryLocalizationTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository
    private lateinit var originalLocale: Locale
    private lateinit var server: TestHttpServer
    private lateinit var base: String

    @Before fun setup() {
        originalLocale = Locale.getDefault()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
        server = TestHttpServer()
        server.start()
        base = "http://127.0.0.1:${server.port}"
    }

    @After fun tearDown() {
        Locale.setDefault(originalLocale)
        server.close()
        db.close()
    }

    /**
     * A pkpass with a webServiceURL/authToken pointing at the given base, so refreshPass has
     * something to poll. Mirrors the helper in PassRepositoryRefreshTest.kt.
     */
    private fun buildPkPass(webServiceUrl: String, serial: String = "SN1"): ByteArray {
        val json = """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.test",
              "serialNumber": "$serial",
              "teamIdentifier": "TEAM",
              "organizationName": "Acme",
              "description": "Test pass",
              "webServiceURL": "$webServiceUrl",
              "authenticationToken": "tok-123",
              "voided": false,
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

    /**
     * A pkpass with a German .lproj translation table for its single secondary field. No
     * `description` on purpose: with no primary fields, computeTitle's fallback chain is
     * description -> organizationName -> "Pass", so leaving description out means the title
     * falls back to (translatable) organizationName, keeping the title-translation assertions
     * below meaningful.
     */
    private fun buildMultilingualPkPass(): ByteArray {
        val passJson = """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.test",
              "serialNumber": "SN1",
              "teamIdentifier": "TEAM",
              "organizationName": "Gate",
              "generic": {
                "secondaryFields": [ { "key": "gate", "label": "Gate", "value": "Open" } ]
              }
            }
        """.trimIndent()
        val strings = """
            "Gate" = "Tor";
            "Open" = "Offen";
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pass.json")); zip.write(passJson.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("de.lproj/pass.strings")); zip.write(strings.toByteArray()); zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test fun `getById translates fields and organization when the current locale matches a bundled lproj`() = runTest {
        val imported = repo.import(buildMultilingualPkPass(), "trip.pkpass")
        Locale.setDefault(Locale.forLanguageTag("de"))

        val loaded = repo.getById(imported.id)!!
        assertEquals("Tor", loaded.organization)
        val field = loaded.fields.first { it.position == FieldPosition.SECONDARY }
        assertEquals("Tor", field.label)
        assertEquals("Offen", field.value)
    }

    @Test fun `the same stored pass renders untranslated when the current locale has no matching lproj`() = runTest {
        val imported = repo.import(buildMultilingualPkPass(), "trip.pkpass")
        Locale.setDefault(Locale.forLanguageTag("fr"))

        val loaded = repo.getById(imported.id)!!
        assertEquals("Gate", loaded.organization)
        val field = loaded.fields.first { it.position == FieldPosition.SECONDARY }
        assertEquals("Gate", field.label)
        assertEquals("Open", field.value)
    }

    @Test fun `observeAll applies the same live translation as getById`() = runTest {
        val imported = repo.import(buildMultilingualPkPass(), "trip.pkpass")
        Locale.setDefault(Locale.forLanguageTag("de"))

        val loaded = repo.observeAll().first().first { it.id == imported.id }
        assertEquals("Tor", loaded.organization)
    }

    @Test fun `a manually-renamed title does not change when the locale changes`() = runTest {
        val imported = repo.import(buildMultilingualPkPass(), "trip.pkpass")
        repo.updateTitle(imported.id, "My Trip")
        Locale.setDefault(Locale.forLanguageTag("de"))

        assertEquals("My Trip", repo.getById(imported.id)!!.title)
    }

    @Test fun `an auto-generated title recomputes live from translated fields`() = runTest {
        val imported = repo.import(buildMultilingualPkPass(), "trip.pkpass")
        assertFalse(imported.titleCustomized)
        // No primary fields in this fixture, so the title falls back to organization ("Gate").
        assertEquals("Gate", imported.title)

        Locale.setDefault(Locale.forLanguageTag("de"))
        assertEquals("Tor", repo.getById(imported.id)!!.title)
    }

    @Test fun `updateTitle marks the pass as customized`() = runTest {
        val imported = repo.import(buildMultilingualPkPass(), "trip.pkpass")
        repo.updateTitle(imported.id, "My Trip")
        assertTrue(repo.getById(imported.id)!!.titleCustomized)
    }

    @Test fun `a manually-renamed title survives a background refresh`() = runTest {
        val imported = repo.import(buildPkPass(base), "trip.pkpass")
        repo.updateTitle(imported.id, "My Trip")

        server.respond("/v1/passes/pass.test/SN1") {
            TestHttpServer.Response(200, buildPkPass(base))
        }

        val result = repo.refreshPass(imported.id)
        check(result is RefreshResult.Updated)
        assertEquals("My Trip", result.pass.title)
        assertTrue(result.pass.titleCustomized)

        val stored = repo.getById(imported.id)!!
        assertEquals("My Trip", stored.title)
        assertTrue(stored.titleCustomized)
    }
}
