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
 * Simulates an existing user's on-disk v2 database (hand-built, matching the schema Room
 * generated for v2 — i.e. v1 plus the `voided`/`lastModified` columns added by [MIGRATION_1_2])
 * and verifies [MIGRATION_2_3] adds `expirationDateEpoch` in place without data loss. See
 * [PassDatabaseMigrationTest] for the analogous v1->v2 case and the rationale for hand-building
 * the file instead of using Room's MigrationTestHelper.
 */
@RunWith(RobolectricTestRunner::class)
class PassDatabaseMigration2Test {
    private val dbName = "migration2-test.db"
    private lateinit var ctx: Context

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(dbName)
    }

    @After fun tearDown() { ctx.deleteDatabase(dbName) }

    @Test fun `migration 2 to 3 preserves existing rows and adds expirationDateEpoch as null`() {
        // Build a v2 database by hand, as an existing installed user's app (pre-this-release) would have.
        ctx.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `passes` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `subtitle` TEXT, `organization` TEXT, `bgColor` INTEGER, " +
                    "`fgColor` INTEGER, `fieldsJson` TEXT NOT NULL, `barcodeJson` TEXT, " +
                    "`relevantDateEpoch` INTEGER, `rawFilePath` TEXT NOT NULL, `sourceFormat` TEXT NOT NULL, " +
                    "`updateInfoJson` TEXT, `voided` INTEGER NOT NULL DEFAULT 0, `lastModified` TEXT, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT INTO passes (id, type, title, subtitle, organization, bgColor, fgColor, " +
                    "fieldsJson, barcodeJson, relevantDateEpoch, rawFilePath, sourceFormat, updateInfoJson, " +
                    "voided, lastModified) " +
                    "VALUES ('p1', 'GENERIC', 'Old Pass', NULL, 'Acme', NULL, NULL, '[]', NULL, NULL, " +
                    "'/tmp/p1.pkpass', 'PKPASS', NULL, 1, 'Wed, 21 Oct 2026 07:28:00 GMT')"
            )
            db.execSQL("PRAGMA user_version = 2")
        }

        // Open the same file at v3 through Room, forcing the real migration chain to run.
        val db3 = Room.databaseBuilder(ctx, PassDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        val stored = runBlocking { db3.passDao().getById("p1") }
        checkNotNull(stored)
        assertEquals("Old Pass", stored.title)
        assertEquals(true, stored.voided)
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", stored.lastModified)
        assertNull(stored.expirationDateEpoch)
        assertNull(stored.toDomain().expirationDate)
        db3.close()
    }
}
