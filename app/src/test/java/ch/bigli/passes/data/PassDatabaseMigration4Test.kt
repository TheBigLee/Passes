package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Simulates an existing user's on-disk v4 database (hand-built, matching the exact schema Room
 * generated for v4 — i.e. v3 plus the `description`/`titleCustomized` columns added by
 * [MIGRATION_3_4]) and verifies [MIGRATION_4_5] rebuilds the table dropping `title`/
 * `titleCustomized` and adding `backFieldsJson`, without losing any other column's data. See
 * [PassDatabaseMigrationTest] for the rationale behind hand-building the file instead of using
 * Room's MigrationTestHelper.
 */
@RunWith(RobolectricTestRunner::class)
class PassDatabaseMigration4Test {
    private val dbName = "migration4-test.db"
    private lateinit var ctx: Context

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(dbName)
    }

    @After fun tearDown() { ctx.deleteDatabase(dbName) }

    @Test fun `migration 4 to 5 drops title and titleCustomized, adds backFieldsJson, preserves everything else`() {
        // Build a v4 database by hand, as an existing installed user's app (pre-this-release) would have.
        ctx.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `passes` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `subtitle` TEXT, `organization` TEXT, `bgColor` INTEGER, " +
                    "`fgColor` INTEGER, `fieldsJson` TEXT NOT NULL, `barcodeJson` TEXT, " +
                    "`relevantDateEpoch` INTEGER, `rawFilePath` TEXT NOT NULL, `sourceFormat` TEXT NOT NULL, " +
                    "`updateInfoJson` TEXT, `voided` INTEGER NOT NULL DEFAULT 0, `lastModified` TEXT, " +
                    "`expirationDateEpoch` INTEGER, `description` TEXT, `titleCustomized` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT INTO passes (id, type, title, subtitle, organization, bgColor, fgColor, " +
                    "fieldsJson, barcodeJson, relevantDateEpoch, rawFilePath, sourceFormat, updateInfoJson, " +
                    "voided, lastModified, expirationDateEpoch, description, titleCustomized) " +
                    "VALUES ('p1', 'GENERIC', 'Old Pass', 'Sub', 'Acme', 123, 456, '[{\"label\":\"A\",\"value\":\"B\",\"position\":\"HEADER\"}]', " +
                    "NULL, NULL, '/tmp/p1.pkpass', 'PKPASS', NULL, 1, 'Wed, 21 Oct 2026 07:28:00 GMT', NULL, 'A description', 1)"
            )
            db.execSQL("PRAGMA user_version = 4")
        }

        // Open the same file at v5 through Room, forcing the real migration chain to run.
        val db5 = Room.databaseBuilder(ctx, PassDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
        val stored = runBlocking { db5.passDao().getById("p1") }
        checkNotNull(stored)
        // Preserved columns:
        assertEquals("Sub", stored.subtitle)
        assertEquals("Acme", stored.organization)
        assertEquals(123L, stored.bgColor)
        assertEquals(456L, stored.fgColor)
        assertEquals("/tmp/p1.pkpass", stored.rawFilePath)
        assertEquals("PKPASS", stored.sourceFormat)
        assertTrue(stored.voided)
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", stored.lastModified)
        assertEquals("A description", stored.description)
        // New column, defaulted:
        assertEquals("[]", stored.backFieldsJson)
        assertEquals(emptyList<ch.bigli.passes.domain.PassField>(), stored.toDomain().backFields)
        db5.close()
    }
}
