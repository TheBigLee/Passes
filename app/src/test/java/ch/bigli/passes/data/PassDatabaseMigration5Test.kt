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
 * Simulates an existing user's on-disk v5 database (hand-built, matching the exact schema Room
 * generated for v5 — see [PassDatabaseMigration4Test]) and verifies [MIGRATION_5_6] adds
 * `autoUpdateEnabled` defaulted to true, without losing any other column's data.
 */
@RunWith(RobolectricTestRunner::class)
class PassDatabaseMigration5Test {
    private val dbName = "migration5-test.db"
    private lateinit var ctx: Context

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(dbName)
    }

    @After fun tearDown() { ctx.deleteDatabase(dbName) }

    @Test fun `migration 5 to 6 adds autoUpdateEnabled defaulted to true, preserves everything else`() {
        // Build a v5 database by hand, as an existing installed user's app (pre-this-release) would have.
        ctx.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `passes` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                    "`subtitle` TEXT, `organization` TEXT, `bgColor` INTEGER, `fgColor` INTEGER, " +
                    "`fieldsJson` TEXT NOT NULL, `barcodeJson` TEXT, `relevantDateEpoch` INTEGER, " +
                    "`rawFilePath` TEXT NOT NULL, `sourceFormat` TEXT NOT NULL, `updateInfoJson` TEXT, " +
                    "`voided` INTEGER NOT NULL DEFAULT 0, `lastModified` TEXT, `expirationDateEpoch` INTEGER, " +
                    "`description` TEXT, `backFieldsJson` TEXT NOT NULL DEFAULT '[]', PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT INTO passes (id, type, subtitle, organization, bgColor, fgColor, fieldsJson, " +
                    "barcodeJson, relevantDateEpoch, rawFilePath, sourceFormat, updateInfoJson, voided, " +
                    "lastModified, expirationDateEpoch, description, backFieldsJson) " +
                    "VALUES ('p1', 'GENERIC', 'Sub', 'Acme', 123, 456, '[{\"label\":\"A\",\"value\":\"B\",\"position\":\"HEADER\"}]', " +
                    "NULL, NULL, '/tmp/p1.pkpass', 'PKPASS', NULL, 1, 'Wed, 21 Oct 2026 07:28:00 GMT', NULL, 'A description', '[]')"
            )
            db.execSQL("PRAGMA user_version = 5")
        }

        // Open the same file at v6 through Room, forcing the real migration chain to run.
        val db6 = Room.databaseBuilder(ctx, PassDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
        val stored = runBlocking { db6.passDao().getById("p1") }
        checkNotNull(stored)
        // Preserved columns:
        assertEquals("Sub", stored.subtitle)
        assertEquals("Acme", stored.organization)
        assertEquals("/tmp/p1.pkpass", stored.rawFilePath)
        assertTrue(stored.voided)
        assertEquals("A description", stored.description)
        // New column, defaulted:
        assertTrue(stored.autoUpdateEnabled)
        assertTrue(stored.toDomain().autoUpdateEnabled)
        db6.close()
    }
}
