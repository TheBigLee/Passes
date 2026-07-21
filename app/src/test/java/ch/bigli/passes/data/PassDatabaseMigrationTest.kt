package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Simulates an existing user's on-disk v1 database (hand-built, matching the exact schema Room
 * generated for v1 before this migration existed) and verifies [MIGRATION_1_2] upgrades it in
 * place without data loss. This intentionally does NOT use Room's `MigrationTestHelper` (which
 * needs schema-export JSON files under test assets) — a hand-built v1 file plus
 * `PRAGMA user_version` is enough to exercise the same code path Android's `SQLiteOpenHelper`
 * uses to decide whether to run a migration, and was confirmed to work before this plan was
 * written.
 */
@RunWith(RobolectricTestRunner::class)
class PassDatabaseMigrationTest {
    private val dbName = "migration-test.db"
    private lateinit var ctx: Context

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(dbName)
    }

    @After fun tearDown() { ctx.deleteDatabase(dbName) }

    @Test fun `migration 1 to 2 preserves existing rows and adds voided lastModified with defaults`() {
        // Build a v1 database by hand, as an existing installed user's app would have created.
        ctx.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `passes` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `subtitle` TEXT, `organization` TEXT, `bgColor` INTEGER, " +
                    "`fgColor` INTEGER, `fieldsJson` TEXT NOT NULL, `barcodeJson` TEXT, " +
                    "`relevantDateEpoch` INTEGER, `rawFilePath` TEXT NOT NULL, `sourceFormat` TEXT NOT NULL, " +
                    "`updateInfoJson` TEXT, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT INTO passes (id, type, title, subtitle, organization, bgColor, fgColor, " +
                    "fieldsJson, barcodeJson, relevantDateEpoch, rawFilePath, sourceFormat, updateInfoJson) " +
                    "VALUES ('p1', 'GENERIC', 'Old Pass', NULL, 'Acme', NULL, NULL, '[]', NULL, NULL, " +
                    "'/tmp/p1.pkpass', 'PKPASS', NULL)"
            )
            db.execSQL("PRAGMA user_version = 1")
        }

        // Open the same file at v2 through Room, forcing the real migration to run.
        val db2 = Room.databaseBuilder(ctx, PassDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2)
            .build()
        val stored = runBlocking { db2.passDao().getById("p1") }
        checkNotNull(stored)
        assertEquals("Old Pass", stored.title)
        assertFalse(stored.voided)
        assertEquals(null, stored.lastModified)
        db2.close()
    }
}
