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

    @Before fun setup() {
        originalLocale = Locale.getDefault()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
    }

    @After fun tearDown() {
        Locale.setDefault(originalLocale)
        db.close()
    }

    /** A pkpass with a German .lproj translation table for its secondary and back fields. */
    private fun buildMultilingualPkPass(): ByteArray {
        val passJson = """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.test",
              "serialNumber": "SN1",
              "teamIdentifier": "TEAM",
              "organizationName": "Gate",
              "generic": {
                "secondaryFields": [ { "key": "gate", "label": "Gate", "value": "Open" } ],
                "backFields": [ { "key": "terms", "label": "Terms", "value": "Refundable" } ]
              }
            }
        """.trimIndent()
        val strings = """
            "Gate" = "Tor";
            "Open" = "Offen";
            "Terms" = "Bedingungen";
            "Refundable" = "Erstattungsfähig";
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

    @Test fun `backFields translate live the same way as the other field lists`() = runTest {
        val imported = repo.import(buildMultilingualPkPass(), "trip.pkpass")
        Locale.setDefault(Locale.forLanguageTag("de"))

        val loaded = repo.getById(imported.id)!!
        assertEquals(1, loaded.backFields.size)
        assertEquals("Bedingungen", loaded.backFields.first().label)
        assertEquals("Erstattungsfähig", loaded.backFields.first().value)
    }
}
