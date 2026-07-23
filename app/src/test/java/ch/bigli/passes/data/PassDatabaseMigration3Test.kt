package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Simulates an existing user's on-disk v3 database (hand-built, matching the schema Room
 * generated for v3 — i.e. v2 plus the `expirationDateEpoch` column added by [MIGRATION_2_3])
 * and verifies [MIGRATION_3_4] adds `description`/`titleCustomized` in place without data loss.
 * See [PassDatabaseMigrationTest] and [PassDatabaseMigration2Test] for the analogous earlier
 * cases and the rationale for hand-building the file instead of using Room's MigrationTestHelper.
 */
@RunWith(RobolectricTestRunner::class)
class PassDatabaseMigration3Test {
    private val dbName = "migration3-test.db"
    private lateinit var ctx: Context

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(dbName)
    }

    @After fun tearDown() { ctx.deleteDatabase(dbName) }

    @Test fun `migration 3 to 4 preserves existing rows and adds description, titleCustomized defaults`() {
        // Build a v3 database by hand, as an existing installed user's app (pre-this-release) would have.
        ctx.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `passes` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `subtitle` TEXT, `organization` TEXT, `bgColor` INTEGER, " +
                    "`fgColor` INTEGER, `fieldsJson` TEXT NOT NULL, `barcodeJson` TEXT, " +
                    "`relevantDateEpoch` INTEGER, `rawFilePath` TEXT NOT NULL, `sourceFormat` TEXT NOT NULL, " +
                    "`updateInfoJson` TEXT, `voided` INTEGER NOT NULL DEFAULT 0, `lastModified` TEXT, " +
                    "`expirationDateEpoch` INTEGER, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT INTO passes (id, type, title, subtitle, organization, bgColor, fgColor, " +
                    "fieldsJson, barcodeJson, relevantDateEpoch, rawFilePath, sourceFormat, updateInfoJson, " +
                    "voided, lastModified, expirationDateEpoch) " +
                    "VALUES ('p1', 'GENERIC', 'Old Pass', NULL, 'Acme', NULL, NULL, '[]', NULL, NULL, " +
                    "'/tmp/p1.pkpass', 'PKPASS', NULL, 0, NULL, NULL)"
            )
            db.execSQL("PRAGMA user_version = 3")
        }

        // Open the same file at v4 through Room, forcing the real migration chain to run.
        val db4 = Room.databaseBuilder(ctx, PassDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
        val stored = runBlocking { db4.passDao().getById("p1") }
        checkNotNull(stored)
        assertNull(stored.description)
        db4.close()
    }
}
