# Detail Screen Redesign + pkpass backFields Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Every gradle command in this repo must be run bare — `./gradlew <task>`, no `JAVA_HOME` prefix. That override is obsolete here (Gradle 9.5's daemon toolchain resolves its own JDK) — do not use it.**

**Goal:** Remove `Pass.title` (a synthesized, non-pkpass value) and the rename feature built around it entirely; add pkpass `backFields` support via a new flip-to-back view on the detail screen, which is also where Delete moves to.

**Architecture:** A cross-cutting removal (title/titleCustomized/computeTitle/rename UI) paired with one addition (`Pass.backFields`), landing in a new "back" composable reached via a persistent bottom-right flip icon on `PassDetailScreen`, using a real 3D flip animation (`graphicsLayer { rotationY = ... }`).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Room (migration v4→v5, table rebuild since dropping columns), Robolectric/JUnit tests.

**⚠️ The build will not compile between Task 1 and Task 5.** `Pass.title` is referenced by UI
files (`PassDetailScreen.kt`, `PassListScreen.kt`, `CreatePassScreen.kt`, `MainActivity.kt`,
`PassApp.kt`) that aren't touched until Task 5. Kotlin/Gradle compiles the whole app module at
once, so **every "run tests" step in Tasks 1–4 will fail with compile errors in files outside
that task's scope** — this is expected, not a mistake. For Tasks 1–4: implement the step exactly
as written, and if the *specific test file(s) named in that step* would compile and pass in
isolation (reason about this by reading, since Gradle can't confirm it yet), treat the step as
done and move on — do not attempt to fix compile errors in files not listed in that task's
**Files** section. Task 5 (Steps 9–10) is the first point a full build/test run is expected to
actually succeed, and is the real verification gate for the whole branch.

---

### Task 1: Domain model — remove title/titleCustomized/computeTitle, add backFields

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/domain/Pass.kt`
- Modify: `app/src/test/java/ch/bigli/passes/domain/PassTest.kt`

- [ ] **Step 1: Update the failing test file first**

Replace the full contents of `app/src/test/java/ch/bigli/passes/domain/PassTest.kt`:

```kotlin
package ch.bigli.passes.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassTest {
    @Test fun `pass exposes primary field values`() {
        val pass = Pass(
            id = "abc",
            type = PassType.BOARDING,
            subtitle = "SWISS",
            organization = "SWISS",
            bgColor = 0xFF1A73E8,
            fgColor = 0xFFFFFFFF,
            fields = listOf(PassField("GATE", "A12", FieldPosition.PRIMARY)),
            barcode = Barcode(BarcodeFormat.QR, "M1SWISS", "M1SWISS"),
            relevantDate = null,
            rawFilePath = "/data/x.pkpass",
            sourceFormat = SourceFormat.PKPASS,
            updateInfo = null,
        )
        assertEquals("A12", pass.fields.first { it.position == FieldPosition.PRIMARY }.value)
        assertTrue(pass.barcode != null)
    }

    @Test fun `backFields defaults to empty`() {
        val pass = Pass(
            id = "abc",
            type = PassType.GENERIC,
            subtitle = null,
            organization = null,
            bgColor = null,
            fgColor = null,
            fields = emptyList(),
            barcode = null,
            relevantDate = null,
            rawFilePath = "/data/x.pkpass",
            sourceFormat = SourceFormat.MANUAL,
            updateInfo = null,
        )
        assertEquals(emptyList<PassField>(), pass.backFields)
    }

    @Test fun `backFields round-trips through copy`() {
        val back = listOf(PassField("Terms", "Non-refundable", FieldPosition.BACK))
        val pass = Pass(
            id = "abc",
            type = PassType.BOARDING,
            subtitle = null,
            organization = null,
            bgColor = null,
            fgColor = null,
            fields = emptyList(),
            backFields = back,
            barcode = null,
            relevantDate = null,
            rawFilePath = "/data/x.pkpass",
            sourceFormat = SourceFormat.PKPASS,
            updateInfo = null,
        )
        assertEquals(back, pass.backFields)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.domain.PassTest"`
Expected: FAIL to compile — `Pass` still requires `title`, doesn't have `backFields`, and `FieldPosition.BACK` doesn't exist.

- [ ] **Step 3: Update `Pass.kt`**

Replace the full contents of `app/src/main/java/ch/bigli/passes/domain/Pass.kt`:

```kotlin
package ch.bigli.passes.domain

import kotlinx.serialization.Serializable
import java.time.Instant

enum class PassType { BOARDING, EVENT, LOYALTY, COUPON, GENERIC }
enum class SourceFormat { PKPASS, GOOGLE_JSON, PDF, MANUAL }
enum class BarcodeFormat { QR, PDF417, AZTEC, CODE128 }
enum class FieldPosition { HEADER, PRIMARY, SECONDARY, AUXILIARY, BACK }

@Serializable
data class PassField(val label: String, val value: String, val position: FieldPosition)

@Serializable
data class Barcode(val format: BarcodeFormat, val message: String, val altText: String?)

@Serializable
data class UpdateInfo(val webServiceUrl: String, val authToken: String, val serialNumber: String, val passTypeId: String)

data class Pass(
    val id: String,
    val type: PassType,
    val subtitle: String?,
    val organization: String?,
    val description: String? = null,
    val bgColor: Long?,
    val fgColor: Long?,
    val fields: List<PassField>,
    val backFields: List<PassField> = emptyList(),
    val barcode: Barcode?,
    val relevantDate: Instant?,
    val expirationDate: Instant? = null,
    val rawFilePath: String,
    val sourceFormat: SourceFormat,
    val updateInfo: UpdateInfo?,
    val voided: Boolean = false,
    val lastModified: String? = null,
) {
    /** True if the issuer declared this pass voided, or its static [expirationDate] has passed. */
    fun isVoidedOrExpired(): Boolean = voided || expirationDate?.isBefore(Instant.now()) == true
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.domain.PassTest"`
Expected: this will fail to compile — removing `Pass.title` immediately breaks every other file
in the module that still references it (`PassDetailScreen.kt`, `PassRepository.kt`,
`PkPassImporter.kt`, etc.), and Gradle compiles the whole module before running any filtered
test. This is expected per the top-of-plan build-state note. Verify `PassTest.kt` itself is
correct by reading it — its 3 cases will genuinely pass once Task 5 lands and the whole module
compiles.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/domain/Pass.kt \
        app/src/test/java/ch/bigli/passes/domain/PassTest.kt
git commit -m "feat: remove Pass.title/titleCustomized/computeTitle, add Pass.backFields"
```

---

### Task 2: Data layer — PassEntity, migration v4→v5, PassDao

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/data/PassEntity.kt`
- Modify: `app/src/main/java/ch/bigli/passes/data/PassDatabase.kt`
- Modify: `app/src/main/java/ch/bigli/passes/data/PassDao.kt`
- Modify: `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigrationTest.kt`
- Modify: `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration2Test.kt`
- Modify: `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration3Test.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration4Test.kt`
- Modify: `app/src/test/java/ch/bigli/passes/data/PassDaoTest.kt`

**Why a table rebuild, not `ALTER TABLE ... DROP COLUMN`:** SQLite only added native `DROP COLUMN`
in 3.35 (2021). Android bundles its own SQLite version tied to the OS version, and this app's
`minSdk` is 26 (Android 8) — `DROP COLUMN` isn't safely available across that whole range. The
portable pattern (used here) is: create a new table with the desired final shape, copy data across
via `INSERT ... SELECT`, drop the old table, rename the new one into place.

- [ ] **Step 1: Write the failing migration test**

Create `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration4Test.kt`:

```kotlin
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.data.PassDatabaseMigration4Test"`
Expected: FAIL to compile — `MIGRATION_4_5` doesn't exist yet, `PassEntity` doesn't have
`backFieldsJson`.

- [ ] **Step 3: Update `PassEntity.kt`**

Replace the full contents of `app/src/main/java/ch/bigli/passes/data/PassEntity.kt`:

```kotlin
package ch.bigli.passes.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import ch.bigli.passes.domain.UpdateInfo
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

@Entity(tableName = "passes")
data class PassEntity(
    @PrimaryKey val id: String,
    val type: String,
    val subtitle: String?,
    val organization: String?,
    val bgColor: Long?,
    val fgColor: Long?,
    val fieldsJson: String,
    val barcodeJson: String?,
    val relevantDateEpoch: Long?,
    val rawFilePath: String,
    val sourceFormat: String,
    val updateInfoJson: String?,
    val voided: Boolean = false,
    val lastModified: String? = null,
    val expirationDateEpoch: Long? = null,
    val description: String? = null,
    val backFieldsJson: String = "[]",
)

fun Pass.toEntity() = PassEntity(
    id = id,
    type = type.name,
    subtitle = subtitle,
    organization = organization,
    bgColor = bgColor,
    fgColor = fgColor,
    fieldsJson = json.encodeToString(ListSerializer(PassField.serializer()), fields),
    barcodeJson = barcode?.let { json.encodeToString(Barcode.serializer(), it) },
    relevantDateEpoch = relevantDate?.toEpochMilli(),
    rawFilePath = rawFilePath,
    sourceFormat = sourceFormat.name,
    updateInfoJson = updateInfo?.let { json.encodeToString(UpdateInfo.serializer(), it) },
    voided = voided,
    lastModified = lastModified,
    expirationDateEpoch = expirationDate?.toEpochMilli(),
    description = description,
    backFieldsJson = json.encodeToString(ListSerializer(PassField.serializer()), backFields),
)

fun PassEntity.toDomain() = Pass(
    id = id,
    type = PassType.valueOf(type),
    subtitle = subtitle,
    organization = organization,
    bgColor = bgColor,
    fgColor = fgColor,
    fields = json.decodeFromString(ListSerializer(PassField.serializer()), fieldsJson),
    backFields = json.decodeFromString(ListSerializer(PassField.serializer()), backFieldsJson),
    barcode = barcodeJson?.let { json.decodeFromString(Barcode.serializer(), it) },
    relevantDate = relevantDateEpoch?.let { java.time.Instant.ofEpochMilli(it) },
    rawFilePath = rawFilePath,
    sourceFormat = SourceFormat.valueOf(sourceFormat),
    updateInfo = updateInfoJson?.let { json.decodeFromString(UpdateInfo.serializer(), it) },
    voided = voided,
    lastModified = lastModified,
    expirationDate = expirationDateEpoch?.let { java.time.Instant.ofEpochMilli(it) },
    description = description,
)
```

- [ ] **Step 4: Add `MIGRATION_4_5` and bump the database version**

Replace the full contents of `app/src/main/java/ch/bigli/passes/data/PassDatabase.kt`:

```kotlin
package ch.bigli.passes.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the columns backing pass-refresh (Phase 4): voided flag + cached Last-Modified header. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE passes ADD COLUMN voided INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE passes ADD COLUMN lastModified TEXT")
    }
}

/** Adds the expirationDate column so expiry can be evaluated live at display time. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE passes ADD COLUMN expirationDateEpoch INTEGER")
    }
}

/**
 * Adds `description` (raw pass.json description, needed to recompute title live) and
 * `titleCustomized` (protects a user-renamed title from being overwritten by live
 * re-translation). Both are superseded by [MIGRATION_4_5] — kept here unchanged since this is
 * exactly how a real v3-installed user's database gets upgraded on the way to v5.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE passes ADD COLUMN description TEXT")
        db.execSQL("ALTER TABLE passes ADD COLUMN titleCustomized INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Drops `title`/`titleCustomized` (the rename feature and the synthesized title it protected are
 * both removed) and adds `backFieldsJson` (pkpass back-of-pass info, live-translated the same way
 * as the other field lists). SQLite's native `DROP COLUMN` (3.35+) isn't safely available across
 * this app's full `minSdk` range, so this rebuilds the table: create the new shape, copy every
 * other column across, drop the old table, rename the new one into place.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE `passes_new` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `subtitle` TEXT, " +
                "`organization` TEXT, `bgColor` INTEGER, `fgColor` INTEGER, `fieldsJson` TEXT NOT NULL, " +
                "`barcodeJson` TEXT, `relevantDateEpoch` INTEGER, `rawFilePath` TEXT NOT NULL, " +
                "`sourceFormat` TEXT NOT NULL, `updateInfoJson` TEXT, `voided` INTEGER NOT NULL DEFAULT 0, " +
                "`lastModified` TEXT, `expirationDateEpoch` INTEGER, `description` TEXT, " +
                "`backFieldsJson` TEXT NOT NULL DEFAULT '[]', PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO passes_new (id, type, subtitle, organization, bgColor, fgColor, fieldsJson, " +
                "barcodeJson, relevantDateEpoch, rawFilePath, sourceFormat, updateInfoJson, voided, " +
                "lastModified, expirationDateEpoch, description, backFieldsJson) " +
                "SELECT id, type, subtitle, organization, bgColor, fgColor, fieldsJson, barcodeJson, " +
                "relevantDateEpoch, rawFilePath, sourceFormat, updateInfoJson, voided, lastModified, " +
                "expirationDateEpoch, description, '[]' FROM passes"
        )
        db.execSQL("DROP TABLE passes")
        db.execSQL("ALTER TABLE passes_new RENAME TO passes")
    }
}

@Database(entities = [PassEntity::class], version = 5, exportSchema = false)
abstract class PassDatabase : RoomDatabase() {
    abstract fun passDao(): PassDao
}
```

- [ ] **Step 5: Update `PassDao.kt`**

Replace the full contents of `app/src/main/java/ch/bigli/passes/data/PassDao.kt`:

```kotlin
package ch.bigli.passes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PassDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PassEntity)

    @Query("SELECT * FROM passes ORDER BY relevantDateEpoch IS NULL, relevantDateEpoch ASC, subtitle ASC")
    fun observeAll(): Flow<List<PassEntity>>

    @Query("SELECT * FROM passes WHERE id = :id")
    suspend fun getById(id: String): PassEntity?

    @Query("DELETE FROM passes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE passes SET voided = 1 WHERE id = :id")
    suspend fun markVoided(id: String)
}
```

- [ ] **Step 6: Update the three earlier migration tests to include `MIGRATION_4_5` in their chain**

Bumping `@Database` version to 5 means Room requires the full migration chain to reach it — every
existing migration test's `Room.databaseBuilder(...).addMigrations(...)` call must now include
`MIGRATION_4_5`, and any assertion on a since-removed column must go.

In `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigrationTest.kt`, replace the migration
list and the title assertion:

```kotlin
        val db2 = Room.databaseBuilder(ctx, PassDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
        val stored = runBlocking { db2.passDao().getById("p1") }
        checkNotNull(stored)
        assertFalse(stored.voided)
        assertEquals(null, stored.lastModified)
        db2.close()
```

(Remove the `assertEquals("Old Pass", stored.title)` line — `title` no longer exists.)

In `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration2Test.kt`, replace the migration
list and drop the title assertion:

```kotlin
        val db3 = Room.databaseBuilder(ctx, PassDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
        val stored = runBlocking { db3.passDao().getById("p1") }
        checkNotNull(stored)
        assertEquals(true, stored.voided)
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", stored.lastModified)
        assertNull(stored.expirationDateEpoch)
        assertNull(stored.toDomain().expirationDate)
        db3.close()
```

In `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration3Test.kt`, replace the migration
list and drop both the title and titleCustomized assertions:

```kotlin
        val db4 = Room.databaseBuilder(ctx, PassDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
        val stored = runBlocking { db4.passDao().getById("p1") }
        checkNotNull(stored)
        assertNull(stored.description)
        db4.close()
```

(Remove `assertEquals("Old Pass", stored.title)` and `assertEquals(false, stored.titleCustomized)`.)

- [ ] **Step 7: Update `PassDaoTest.kt`**

Replace the full contents of `app/src/test/java/ch/bigli/passes/data/PassDaoTest.kt`:

```kotlin
package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassDaoTest {
    private lateinit var db: PassDatabase
    private lateinit var dao: PassDao

    private fun sample(id: String) = Pass(
        id = id, type = PassType.EVENT, subtitle = "Venue",
        organization = "Org", bgColor = 0xFF34A853, fgColor = 0xFFFFFFFF,
        fields = listOf(PassField("WHEN", "21:00", FieldPosition.PRIMARY)),
        barcode = Barcode(BarcodeFormat.QR, "XYZ", "XYZ"),
        relevantDate = null, rawFilePath = "/data/$id.pkpass",
        sourceFormat = SourceFormat.PKPASS, updateInfo = null,
    )

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        dao = db.passDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `insert then observe returns the pass`() = runTest {
        dao.insert(sample("a").toEntity())
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        val roundTripped = all.first().toDomain()
        assertEquals("Org", roundTripped.organization)
        assertEquals(BarcodeFormat.QR, roundTripped.barcode!!.format)
        assertEquals("21:00", roundTripped.fields.first().value)
    }

    @Test fun `getById then delete removes it`() = runTest {
        dao.insert(sample("b").toEntity())
        assertEquals("Org", dao.getById("b")!!.toDomain().organization)
        dao.deleteById("b")
        assertNull(dao.getById("b"))
    }
}
```

- [ ] **Step 8: Run all data-layer tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.data.*"`
Expected: this will still fail to compile at this point (see the top-of-plan build-state note —
`PassRepository` and the UI layer aren't fixed until Tasks 4-5). Verify correctness by reading
the diff carefully instead; Task 5 is where a real green run happens.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/data/PassEntity.kt \
        app/src/main/java/ch/bigli/passes/data/PassDatabase.kt \
        app/src/main/java/ch/bigli/passes/data/PassDao.kt \
        app/src/test/java/ch/bigli/passes/data/PassDatabaseMigrationTest.kt \
        app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration2Test.kt \
        app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration3Test.kt \
        app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration4Test.kt \
        app/src/test/java/ch/bigli/passes/data/PassDaoTest.kt
git commit -m "feat: v4->v5 migration dropping title/titleCustomized, adding backFieldsJson"
```

---

### Task 3: Importing — parse backFields, drop title from PkPassImporter/PdfImporter

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/importing/PkPassJson.kt`
- Modify: `app/src/main/java/ch/bigli/passes/importing/PkPassImporter.kt`
- Modify: `app/src/main/java/ch/bigli/passes/importing/PdfImporter.kt`
- Modify: `app/src/test/java/ch/bigli/passes/importing/PkPassImporterTest.kt`

- [ ] **Step 1: Write the failing test for backFields parsing**

In `app/src/test/java/ch/bigli/passes/importing/PkPassImporterTest.kt`, remove the two tests that
existed purely to support `computeTitle`/title (`stores the raw description for later live title
recomputation` and `a primary field with no label falls back to its value for both display and
title`), and add this one in their place (append inside the existing `PkPassImporterTest` class):

```kotlin
    @Test fun `parses backFields into Pass backFields`() {
        val passJson = """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.test",
              "serialNumber": "SN1",
              "teamIdentifier": "TEAM",
              "organizationName": "Acme",
              "generic": {
                "primaryFields": [],
                "backFields": [
                  { "key": "terms", "label": "Terms", "value": "Non-refundable" }
                ]
              }
            }
        """.trimIndent()
        val pass = importer.import(buildPkPass(passJson), "/data/x.pkpass", "x.pkpass")
        assertEquals(1, pass.backFields.size)
        assertEquals("Terms", pass.backFields.first().label)
        assertEquals("Non-refundable", pass.backFields.first().value)
        assertEquals(FieldPosition.BACK, pass.backFields.first().position)
    }

    @Test fun `backFields defaults to empty when absent`() {
        val passJson = """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.test",
              "serialNumber": "SN1",
              "teamIdentifier": "TEAM",
              "organizationName": "Acme",
              "generic": { "primaryFields": [] }
            }
        """.trimIndent()
        val pass = importer.import(buildPkPass(passJson), "/data/x.pkpass", "x.pkpass")
        assertEquals(emptyList<Any>(), pass.backFields)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.importing.PkPassImporterTest"`
Expected: FAIL to compile — `PkStructure` doesn't have `backFields` yet, and `PkPassImporter`
still requires/returns `title`.

- [ ] **Step 3: Add `backFields` to `PkStructure`**

In `app/src/main/java/ch/bigli/passes/importing/PkPassJson.kt`, update `PkStructure`:

```kotlin
@Serializable
data class PkStructure(
    val headerFields: List<PkField>? = null,
    val primaryFields: List<PkField>? = null,
    val secondaryFields: List<PkField>? = null,
    val auxiliaryFields: List<PkField>? = null,
    val backFields: List<PkField>? = null,
)
```

- [ ] **Step 4: Update `PkPassImporter.kt`**

Replace the full contents of `app/src/main/java/ch/bigli/passes/importing/PkPassImporter.kt`:

```kotlin
package ch.bigli.passes.importing

import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import ch.bigli.passes.domain.UpdateInfo
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

class PkPassImporter : PassImporter {
    private val json = Json { ignoreUnknownKeys = true }

    override fun import(bytes: ByteArray, rawFilePath: String, @Suppress("UNUSED_PARAMETER") displayName: String): Pass {
        val passJsonBytes = extractPassJson(bytes)
        val pj = try {
            json.decodeFromString(PkPassJson.serializer(), passJsonBytes.decodeToString())
        } catch (e: Exception) {
            throw ImportError.CorruptFile("pass.json unparseable: ${e.message}")
        }

        val (type, structure) = resolveStructure(pj)
        val fields = buildList {
            structure?.headerFields?.forEach { add(it.toField(FieldPosition.HEADER)) }
            structure?.primaryFields?.forEach { add(it.toField(FieldPosition.PRIMARY)) }
            structure?.secondaryFields?.forEach { add(it.toField(FieldPosition.SECONDARY)) }
            structure?.auxiliaryFields?.forEach { add(it.toField(FieldPosition.AUXILIARY)) }
        }
        val backFields = structure?.backFields.orEmpty().map { it.toField(FieldPosition.BACK) }

        val update = if (!pj.webServiceURL.isNullOrBlank() && !pj.authenticationToken.isNullOrBlank()
            && !pj.serialNumber.isNullOrBlank() && !pj.passTypeIdentifier.isNullOrBlank()
        ) {
            UpdateInfo(pj.webServiceURL, pj.authenticationToken, pj.serialNumber, pj.passTypeIdentifier)
        } else null

        return Pass(
            id = UUID.randomUUID().toString(),
            type = type,
            subtitle = pj.organizationName,
            organization = pj.organizationName,
            description = pj.description,
            bgColor = parseColor(pj.backgroundColor),
            fgColor = parseColor(pj.foregroundColor),
            fields = fields,
            backFields = backFields,
            barcode = resolveBarcode(pj),
            relevantDate = pj.relevantDate?.let { runCatching { Instant.parse(it) }.getOrNull() },
            rawFilePath = rawFilePath,
            sourceFormat = SourceFormat.PKPASS,
            updateInfo = update,
            expirationDate = pj.expirationDate?.let { runCatching { Instant.parse(it) }.getOrNull() },
            voided = pj.voided ?: false,
        )
    }

    private fun extractPassJson(bytes: ByteArray): ByteArray {
        val out = try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                var found: ByteArray? = null
                while (entry != null) {
                    if (entry.name == "pass.json") { found = zip.readBytes() }
                    entry = zip.nextEntry
                }
                found
            }
        } catch (e: Exception) {
            throw ImportError.CorruptFile("not a valid zip: ${e.message}")
        }
        return out ?: throw ImportError.CorruptFile("pass.json missing")
    }

    private fun resolveStructure(pj: PkPassJson): Pair<PassType, PkStructure?> = when {
        pj.boardingPass != null -> PassType.BOARDING to pj.boardingPass
        pj.eventTicket != null -> PassType.EVENT to pj.eventTicket
        pj.storeCard != null -> PassType.LOYALTY to pj.storeCard
        pj.coupon != null -> PassType.COUPON to pj.coupon
        else -> PassType.GENERIC to pj.generic
    }

    private fun resolveBarcode(pj: PkPassJson): Barcode? {
        val b = pj.barcode ?: pj.barcodes?.firstOrNull() ?: return null
        val fmt = when (b.format) {
            "PKBarcodeFormatQR" -> BarcodeFormat.QR
            "PKBarcodeFormatPDF417" -> BarcodeFormat.PDF417
            "PKBarcodeFormatAztec" -> BarcodeFormat.AZTEC
            "PKBarcodeFormatCode128" -> BarcodeFormat.CODE128
            else -> BarcodeFormat.QR
        }
        return Barcode(fmt, b.message, b.altText)
    }

    /** Apple uses "rgb(r, g, b)". Returns 0xAARRGGBB or null. */
    private fun parseColor(s: String?): Long? {
        if (s == null) return null
        val nums = Regex("""\d+""").findAll(s).map { it.value.toInt() }.toList()
        if (nums.size < 3) return null
        val (r, g, b) = nums
        return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    private fun PkField.toField(pos: FieldPosition) = PassField(
        label = if (pos == FieldPosition.PRIMARY) (label ?: value) else (label ?: key),
        value = value,
        position = pos,
    )
}
```

- [ ] **Step 5: Update `PdfImporter.kt`**

In `app/src/main/java/ch/bigli/passes/importing/PdfImporter.kt`, replace the `import` method body:

```kotlin
    override fun import(bytes: ByteArray, rawFilePath: String, displayName: String): Pass {
        val barcode = extractBarcode(File(rawFilePath)) ?: throw ImportError.NoBarcode(displayName)
        return Pass(
            id = UUID.randomUUID().toString(),
            type = PassType.GENERIC,
            subtitle = null,
            organization = null,
            bgColor = null,
            fgColor = null,
            fields = emptyList(),
            barcode = barcode,
            relevantDate = null,
            rawFilePath = rawFilePath,
            sourceFormat = SourceFormat.PDF,
            updateInfo = null,
        )
    }
```

(This drops the `base`/`title` computation entirely — `displayName` is now unused by the body but
stays as a parameter since it's part of the shared `PassImporter` interface.)

- [ ] **Step 6: Run the importer tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.importing.*"`
Expected: this will still fail to compile at this point (see the top-of-plan build-state note —
`PassRepository`/UI files aren't updated until Tasks 4-5). Verify correctness by reading the
diff carefully instead; Task 5 is where a real green run happens.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/importing/PkPassJson.kt \
        app/src/main/java/ch/bigli/passes/importing/PkPassImporter.kt \
        app/src/main/java/ch/bigli/passes/importing/PdfImporter.kt \
        app/src/test/java/ch/bigli/passes/importing/PkPassImporterTest.kt
git commit -m "feat: parse pkpass backFields, drop title from PkPassImporter/PdfImporter"
```

---

### Task 4: Repository — drop updateTitle, createManualPass signature, localize() backFields translation

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`
- Delete: `app/src/test/java/ch/bigli/passes/data/PassRepositoryUpdateTest.kt`
- Modify: `app/src/test/java/ch/bigli/passes/data/PassRepositoryLocalizationTest.kt`
- Modify: `app/src/test/java/ch/bigli/passes/data/PassRepositoryRefreshTest.kt`
- Modify: `app/src/test/java/ch/bigli/passes/data/PassRepositoryManualTest.kt`

- [ ] **Step 1: Delete `PassRepositoryUpdateTest.kt` entirely**

It exists solely to test `updateTitle`, which is being removed.

```bash
git rm app/src/test/java/ch/bigli/passes/data/PassRepositoryUpdateTest.kt
```

- [ ] **Step 2: Update `PassRepositoryManualTest.kt`'s call site (will fail to compile until Step 5)**

Replace the full contents of `app/src/test/java/ch/bigli/passes/data/PassRepositoryManualTest.kt`:

```kotlin
package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassRepositoryManualTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao())
    }

    @After fun tearDown() = db.close()

    @Test fun `createManualPass stores a MANUAL generic pass`() = runTest {
        val pass = repo.createManualPass(BarcodeFormat.CODE128, "6001234567890")
        val stored = repo.getById(pass.id)!!
        assertEquals(PassType.GENERIC, stored.type)
        assertEquals(SourceFormat.MANUAL, stored.sourceFormat)
        assertEquals(BarcodeFormat.CODE128, stored.barcode!!.format)
        assertEquals("6001234567890", stored.barcode!!.message)
        assertEquals("", stored.rawFilePath)
        assertEquals(1, repo.observeAll().first().size)
    }
}
```

- [ ] **Step 3: Update `PassRepositoryRefreshTest.kt`'s title-dependent assertions**

In `app/src/test/java/ch/bigli/passes/data/PassRepositoryRefreshTest.kt`, replace the first test
(dropping the `updateTitle` call and title assertions, keeping everything else about the 200
refresh behavior):

```kotlin
    @Test fun `refreshPass on 200 replaces fields, stores Last-Modified`() = runTest {
        val imported = repo.import(buildPkPass(base), "test.pkpass")

        server.respond(path) {
            TestHttpServer.Response(
                200, buildPkPass(base, organizationName = "Acme Updated"),
                headers = mapOf("Last-Modified" to "Wed, 21 Oct 2026 07:28:00 GMT"),
            )
        }

        val result = repo.refreshPass(imported.id)
        check(result is RefreshResult.Updated)
        assertEquals(imported.id, result.pass.id)
        assertEquals("Acme Updated", result.pass.organization)
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", result.pass.lastModified)

        val stored = repo.getById(imported.id)!!
        assertEquals("Acme Updated", stored.organization)
    }
```

The last test in that file (`refreshPass on a pass without updateInfo returns NotUpdatable without
a network call`) calls `repo.createManualPass("Coop card", BarcodeFormat.CODE128, "6001234567890")`
— update that call site too:

```kotlin
    @Test fun `refreshPass on a pass without updateInfo returns NotUpdatable without a network call`() = runTest {
        val manual = repo.createManualPass(BarcodeFormat.CODE128, "6001234567890")
        val result = repo.refreshPass(manual.id)
        assertEquals(RefreshResult.NotUpdatable, result)
    }
```

- [ ] **Step 4: Rewrite `PassRepositoryLocalizationTest.kt` — drop title tests, add backFields translation test**

Replace the full contents of `app/src/test/java/ch/bigli/passes/data/PassRepositoryLocalizationTest.kt`:

```kotlin
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
```

- [ ] **Step 5: Update `PassRepository.kt`**

Replace the full contents of `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`:

```kotlin
package ch.bigli.passes.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import ch.bigli.passes.importing.PassImporter
import ch.bigli.passes.importing.PdfImporter
import ch.bigli.passes.importing.PkPassImporter
import ch.bigli.passes.importing.PkPassLocalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

sealed interface RefreshResult {
    data class Updated(val pass: Pass) : RefreshResult
    data object Unchanged : RefreshResult
    data object Voided : RefreshResult
    data object NotUpdatable : RefreshResult
    data class Error(val message: String) : RefreshResult
}

class PassRepository(
    private val context: Context,
    private val dao: PassDao,
    private val pkPassImporter: PkPassImporter = PkPassImporter(),
    private val pdfImporter: PdfImporter = PdfImporter(),
) {
    fun observeAll(): Flow<List<Pass>> = dao.observeAll().map { list -> list.map { localize(it.toDomain()) } }

    suspend fun getById(id: String): Pass? = withContext(Dispatchers.IO) { dao.getById(id)?.toDomain()?.let { localize(it) } }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.getById(id)?.let { runCatching { File(it.rawFilePath).delete() } }
        dao.deleteById(id)
    }

    /**
     * Applies live pkpass localization: re-reads the pass's raw zip (still on disk, unmodified)
     * and translates field labels/values (including backFields), organization, subtitle, and
     * description against whatever language `Locale.getDefault()` currently returns — never
     * baked in at import time, so a device language change takes effect on the next read without
     * a re-import. Non-pkpass passes, and any pass whose raw file can't be read, pass through
     * unchanged.
     */
    private fun localize(pass: Pass): Pass {
        if (pass.sourceFormat != SourceFormat.PKPASS) return pass
        val bytes = runCatching { File(pass.rawFilePath).readBytes() }.getOrNull() ?: return pass
        val localization = runCatching { PkPassLocalization.forZip(bytes) }.getOrNull() ?: return pass

        fun translateField(f: ch.bigli.passes.domain.PassField) = f.copy(
            label = localization.translate(f.label) ?: f.label,
            value = localization.translate(f.value) ?: f.value,
        )

        return pass.copy(
            fields = pass.fields.map(::translateField),
            backFields = pass.backFields.map(::translateField),
            organization = localization.translate(pass.organization),
            subtitle = localization.translate(pass.subtitle),
            description = localization.translate(pass.description),
        )
    }

    /** Creates a pass from a manually-entered or scanned barcode (no source file). */
    suspend fun createManualPass(format: BarcodeFormat, value: String): Pass =
        withContext(Dispatchers.IO) {
            val pass = Pass(
                id = UUID.randomUUID().toString(),
                type = PassType.GENERIC,
                subtitle = null,
                organization = null,
                bgColor = null,
                fgColor = null,
                fields = emptyList(),
                barcode = Barcode(format, value, null),
                relevantDate = null,
                rawFilePath = "",
                sourceFormat = SourceFormat.MANUAL,
                updateInfo = null,
            )
            dao.insert(pass.toEntity())
            pass
        }

    /** Detects the format from [bytes], persists the raw file, imports, and stores the pass. */
    suspend fun import(bytes: ByteArray, displayName: String): Pass = withContext(Dispatchers.IO) {
        val (importer, ext) = when (detectPassFormat(bytes)) {
            SourceFormat.PKPASS -> pkPassImporter as PassImporter to "pkpass"
            SourceFormat.PDF -> pdfImporter as PassImporter to "pdf"
            else -> throw ImportError.UnsupportedFormat(displayName)
        }
        val dir = File(context.filesDir, "passes").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.$ext")
        target.writeBytes(bytes)
        val pass = try {
            importer.import(bytes, target.absolutePath, displayName)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        dao.insert(pass.toEntity())
        pass
    }

    /**
     * Reads the bytes behind [uri] (a content:// or file:// document) off the main thread and
     * imports them through [import]. Used by the file picker and by "Open with"/share intents.
     */
    suspend fun importFromUri(uri: Uri): Pass = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImportError.CorruptFile("could not open $uri")
        import(bytes, displayName(uri))
    }

    /**
     * Downloads a .pkpass from [url] (http/https) off the main thread and imports it. Used by the
     * walletpasses:// "Add to Wallet" web flow. Throws [ImportError.CorruptFile] on a network/HTTP failure.
     */
    suspend fun importFromUrl(url: String): Pass = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        val bytes = try {
            val code = conn.responseCode
            if (code !in 200..299) throw ImportError.CorruptFile("download failed: HTTP $code")
            conn.inputStream.use { it.readBytes() }
        } catch (e: ImportError) {
            throw e
        } catch (e: Exception) {
            throw ImportError.CorruptFile("download failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "pass.pkpass" }
        import(bytes, name)
    }

    /**
     * Polls the pass's `webServiceURL` (Apple PassKit web service protocol) for a fresher pkpass.
     * No-ops (returns [RefreshResult.NotUpdatable]) for passes without update info, non-pkpass
     * passes, and already-voided passes — none of these make a network request.
     */
    suspend fun refreshPass(id: String): RefreshResult = withContext(Dispatchers.IO) {
        val pass = dao.getById(id)?.toDomain() ?: return@withContext RefreshResult.NotUpdatable
        val update = pass.updateInfo
        if (pass.sourceFormat != SourceFormat.PKPASS || update == null || pass.voided) {
            return@withContext RefreshResult.NotUpdatable
        }
        val url = "${update.webServiceUrl.trimEnd('/')}/v1/passes/${update.passTypeId}/${update.serialNumber}"
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Authorization", "ApplePass ${update.authToken}")
                pass.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
            }
        } catch (e: Exception) {
            return@withContext RefreshResult.Error("connection failed: ${e.message}")
        }
        try {
            when (val code = conn.responseCode) {
                200 -> {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val fresh = try {
                        pkPassImporter.import(bytes, pass.rawFilePath, "")
                    } catch (e: Exception) {
                        return@withContext RefreshResult.Error("malformed update: ${e.message}")
                    }
                    // Write to a temp file then rename, so a concurrent image read via
                    // ZipFile(rawFilePath) never sees a partially-written zip.
                    val target = File(pass.rawFilePath)
                    val tmp = File(target.parentFile, "${target.name}.tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(target)
                    val merged = fresh.copy(
                        id = pass.id,
                        // Deliberately NOT forced to false: trust whatever the freshly re-imported
                        // pass.json says, so an issuer that still declares the pass voided stays voided.
                        voided = fresh.voided,
                        lastModified = conn.getHeaderField("Last-Modified") ?: pass.lastModified,
                    )
                    dao.insert(merged.toEntity())
                    RefreshResult.Updated(merged)
                }
                304 -> RefreshResult.Unchanged
                410 -> {
                    dao.markVoided(pass.id)
                    RefreshResult.Voided
                }
                else -> RefreshResult.Error("unexpected status $code")
            }
        } catch (e: Exception) {
            RefreshResult.Error("refresh failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment ?: "pass.pkpass"
    }
}
```

- [ ] **Step 6: Run all data-layer tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.data.*"`
Expected: this will still fail to compile at this point (see the top-of-plan build-state note —
the UI layer isn't updated until Task 5). Verify correctness by reading the diff carefully
instead; Task 5 is where a real green run happens.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/data/PassRepository.kt \
        app/src/test/java/ch/bigli/passes/data/PassRepositoryLocalizationTest.kt \
        app/src/test/java/ch/bigli/passes/data/PassRepositoryRefreshTest.kt \
        app/src/test/java/ch/bigli/passes/data/PassRepositoryManualTest.kt \
        app/src/test/java/ch/bigli/passes/data/PassRepositoryUpdateTest.kt
git commit -m "feat: drop updateTitle/title from PassRepository, translate backFields live"
```

---

### Task 5: UI — remove title/rename UI, add flip-to-back with backFields + Delete

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/ui/CreatePassScreen.kt`
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt`
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt`
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassDetailViewModel.kt`
- Modify: `app/src/main/java/ch/bigli/passes/PassApp.kt`
- Modify: `app/src/main/java/ch/bigli/passes/MainActivity.kt`
- Modify: `app/src/test/java/ch/bigli/passes/ui/PassDetailViewModelTest.kt`

- [ ] **Step 1: Update `PassDetailViewModelTest.kt` first (drives the ViewModel change)**

Replace the full contents of `app/src/test/java/ch/bigli/passes/ui/PassDetailViewModelTest.kt`:

```kotlin
package ch.bigli.passes.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.data.PassDatabase
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassDetailViewModelTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun `loads the pass by id on init`() = runTest {
        val imported = repo.import(fixture("sample.pkpass"), "sample.pkpass")
        val vm = PassDetailViewModel(repo, imported.id)
        val loaded = vm.pass.first { it != null }
        assertEquals(imported.id, loaded?.id)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.ui.PassDetailViewModelTest"`
Expected: FAIL to compile — `PassDetailViewModel.updateTitle` still exists and is unused by this
test now, but the test file itself compiles fine; the real failure is elsewhere in the module
until Steps 3-6 land. Proceed to the implementation steps.

- [ ] **Step 3: Update `PassDetailViewModel.kt` — remove `updateTitle`**

Replace the full contents of `app/src/main/java/ch/bigli/passes/ui/PassDetailViewModel.kt`:

```kotlin
package ch.bigli.passes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.data.RefreshResult
import ch.bigli.passes.domain.Pass
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PassDetailViewModel(private val repo: PassRepository, private val passId: String) : ViewModel() {
    private val _pass = MutableStateFlow<Pass?>(null)
    val pass: StateFlow<Pass?> = _pass

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _refreshMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val refreshMessage: SharedFlow<String> = _refreshMessage

    init {
        viewModelScope.launch { _pass.value = repo.getById(passId) }
    }

    /** Polls this pass's webServiceURL for a fresher pkpass; reflects the result on screen. */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = repo.refreshPass(passId)
            _pass.value = repo.getById(passId)
            _refreshMessage.tryEmit(
                when (result) {
                    is RefreshResult.Updated -> "Pass updated"
                    RefreshResult.Unchanged -> "Up to date"
                    RefreshResult.Voided -> "This pass has been voided"
                    RefreshResult.NotUpdatable -> "This pass can't be refreshed"
                    is RefreshResult.Error -> "Couldn't refresh"
                },
            )
            _isRefreshing.value = false
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { repo.delete(passId); onDone() }
    }
}
```

- [ ] **Step 4: Update `CreatePassScreen.kt` — remove the title field**

Replace the full contents of `app/src/main/java/ch/bigli/passes/ui/CreatePassScreen.kt`:

```kotlin
package ch.bigli.passes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePassScreen(
    prefill: Barcode?,
    onCreate: (format: BarcodeFormat, value: String) -> Unit,
    onBack: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(prefill?.message ?: "") }
    var format by remember { mutableStateOf(prefill?.format ?: BarcodeFormat.QR) }
    var expanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New pass") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Barcode value") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = format.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Format") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    BarcodeFormat.entries.forEach { fmt ->
                        DropdownMenuItem(
                            text = { Text(fmt.name) },
                            onClick = { format = fmt; expanded = false },
                        )
                    }
                }
            }
            Button(
                onClick = { onCreate(format, value) },
                enabled = value.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create pass") }
        }
    }
}
```

- [ ] **Step 5: Update `PassListScreen.kt` — remove the title `Text`**

In `app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt`, in the `PassCard` composable, remove
this line (keep everything else in the function as-is):

```kotlin
        Text(pass.title, color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
```

- [ ] **Step 6: Rewrite `PassDetailScreen.kt` — remove title/edit/delete-in-app-bar, add flip + back view**

Replace the full contents of `app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt`:

```kotlin
package ch.bigli.passes.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bigli.passes.barcode.BarcodeRenderer
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.images.PassImage
import ch.bigli.passes.images.PassImageLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassDetailScreen(
    viewModel: PassDetailViewModel,
    imageLoader: PassImageLoader,
    onBack: () -> Unit,
) {
    val pass by viewModel.pass.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var flipped by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refreshMessage.collect { snackbar.showSnackbar(it) }
    }

    // Boost brightness while this screen is visible; restore on exit.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val original = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        onDispose {
            window?.attributes = window?.attributes?.apply {
                screenBrightness = original ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    val p = pass
    val bgColor = p?.bgColor
    val bg = bgColor?.let { Color(it) } ?: Color(0xFF1A73E8)
    val fg = if (bgColor != null) Color(legibleTextColor(bgColor, p?.fgColor)) else Color.White

    val rawPath = p?.rawFilePath
    // Keyed on lastModified too, so a successful refresh (which overwrites the pkpass file
    // in place) causes the logo/strip images to reload instead of showing stale artwork.
    val logo by produceState<Bitmap?>(initialValue = null, rawPath, p?.lastModified) {
        value = rawPath?.let { imageLoader.load(it, PassImage.LOGO) }
    }
    val strip by produceState<Bitmap?>(initialValue = null, rawPath, p?.lastModified) {
        value = rawPath?.let { imageLoader.load(it, PassImage.STRIP) }
    }

    val rotation by animateFloatAsState(targetValue = if (flipped) 180f else 0f, animationSpec = tween(600), label = "cardFlip")

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = {
                    val currentLogo = logo
                    if (currentLogo != null) {
                        Image(
                            currentLogo.asImageBitmap(),
                            contentDescription = p?.organization,
                            modifier = Modifier.height(28.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(p?.organization ?: "")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = fg)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = fg),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (p == null) return@Scaffold
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationY = rotation
                        cameraDistance = 8 * density
                    },
            ) {
                if (rotation <= 90f) {
                    PassFrontContent(
                        pass = p,
                        bg = bg,
                        fg = fg,
                        strip = strip,
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.refresh() },
                    )
                } else {
                    Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                        PassBackContent(pass = p, bg = bg, fg = fg, onDelete = { viewModel.delete(onBack) })
                    }
                }
            }
            IconButton(
                onClick = { flipped = !flipped },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Icon(Icons.Filled.Info, contentDescription = if (flipped) "Show front of pass" else "Show back of pass", tint = fg)
            }
        }
    }
}

@Composable
private fun PassFrontContent(
    pass: Pass,
    bg: Color,
    fg: Color,
    strip: Bitmap?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        val isVoidedOrExpired = pass.isVoidedOrExpired()
        Column(Modifier.fillMaxSize().background(bg)) {
            strip?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    pass.fields.filter { it.position != FieldPosition.PRIMARY }.take(4).forEach { f ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
                            Text(f.value, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.size(32.dp))
                pass.barcode?.let { bc ->
                    val renderer = remember { BarcodeRenderer() }
                    val square = bc.format == BarcodeFormat.QR || bc.format == BarcodeFormat.AZTEC
                    val bmp = remember(bc) {
                        if (square) renderer.render(bc, 600, 600) else renderer.render(bc, 800, 300)
                    }
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(16.dp)
                            .alpha(if (isVoidedOrExpired) 0.35f else 1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(bmp.asImageBitmap(), contentDescription = "Barcode", modifier = Modifier.size(240.dp))
                        if (pass.voided) {
                            Text(
                                "This pass has been voided by the issuer",
                                color = Color.Black,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else if (pass.expirationDate?.isBefore(java.time.Instant.now()) == true) {
                            Text(
                                "This pass has expired",
                                color = Color.Black,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        bc.altText?.let {
                            Text(it, color = Color.Black, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Text(
                        "☀ Screen brightened for scanning",
                        color = fg.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } ?: Text("No barcode on this pass", color = fg)
            }
        }
    }
}

@Composable
private fun PassBackContent(pass: Pass, bg: Color, fg: Color, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        pass.backFields.forEach { f ->
            Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 11.sp)
            Text(f.value, color = fg, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        Spacer(Modifier.size(24.dp))
        Button(
            onClick = onDelete,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = fg)
            Spacer(Modifier.size(8.dp))
            Text("Delete pass", color = fg)
        }
    }
}
```

- [ ] **Step 7: Update `PassApp.kt` — simplify `PendingPass`**

In `app/src/main/java/ch/bigli/passes/PassApp.kt`, replace the doc comment and data class at the
bottom of the file:

```kotlin
/** A just-imported pass to navigate to. */
data class PendingPass(val id: String)
```

- [ ] **Step 8: Update `MainActivity.kt` — simplify nav route, `toPending`, `createManualPass` call site**

In `app/src/main/java/ch/bigli/passes/MainActivity.kt`:

Remove the now-unused import:

```kotlin
import ch.bigli.passes.domain.SourceFormat
```

Replace the `toPending` extension function:

```kotlin
private fun Pass.toPending() = PendingPass(id)
```

Replace the `LaunchedEffect(pending)` navigation call:

```kotlin
    val pending by app.pendingPass.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { p ->
            nav.navigate("detail/${p.id}")
            app.pendingPass.value = null
        }
    }
```

Replace the `create` composable's `onCreate` lambda:

```kotlin
        composable("create") {
            val scope = rememberCoroutineScope()
            val prefill: Barcode? = remember { app.pendingScan.value }
            CreatePassScreen(
                prefill = prefill,
                onCreate = { format, value ->
                    scope.launch {
                        val pass = repo.createManualPass(format, value)
                        app.pendingScan.value = null
                        nav.navigate("detail/${pass.id}") { popUpTo("list") }
                    }
                },
                onBack = { app.pendingScan.value = null; nav.popBackStack() },
            )
        }
```

Replace the `detail/{id}...` composable (drops the `editTitle` argument and the
`openTitleEditor` parameter):

```kotlin
        composable(
            "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("id")!!
            val vm: PassDetailViewModel = viewModel(factory = VmFactory { PassDetailViewModel(repo, id) })
            PassDetailScreen(
                viewModel = vm,
                imageLoader = imageLoader,
                onBack = { nav.popBackStack() },
            )
        }
```

- [ ] **Step 9: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Build the debug APK to catch any remaining Compose compile errors**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/ui/CreatePassScreen.kt \
        app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt \
        app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt \
        app/src/main/java/ch/bigli/passes/ui/PassDetailViewModel.kt \
        app/src/main/java/ch/bigli/passes/PassApp.kt \
        app/src/main/java/ch/bigli/passes/MainActivity.kt \
        app/src/test/java/ch/bigli/passes/ui/PassDetailViewModelTest.kt
git commit -m "feat: remove title/rename UI, add flip-to-back detail screen with backFields+Delete"
```

---

### Task 6: Device verification

Not a code task — manual checks on the real Pixel device before merging.

- [ ] **Step 1: Build and install**

Run: `./gradlew :app:installDebug`

- [ ] **Step 2: Verify existing passes migrate and open cleanly**

Open the app with your existing imported passes. Confirm:
- No crash on launch (the v4→v5 table-rebuild migration runs on your real, populated database).
- Every existing pass still opens, shows its fields/barcode/images correctly, with no title text
  anywhere (list row or detail screen).

- [ ] **Step 3: Verify the flip and Delete**

- On any pass's detail screen, confirm the bottom-right info icon is present and tapping it plays
  a visible 3D flip to a back view (background color carried over, content readable, not mirrored).
- Confirm Delete on the back view actually deletes the pass and returns to the list.
- Confirm flipping back (tapping the icon again) returns to the front correctly.

- [ ] **Step 4: Verify backFields, if you have a real pkpass with them**

Import a pkpass that has `backFields` in its `pass.json` and confirm they render as label/value
pairs on the back view, above the Delete button.

- [ ] **Step 5: Verify manual pass creation still works**

Create a manual pass (scan or type a barcode). Confirm it's created without asking for a title,
and appears in the list (with degraded identification — just its generic type — which is the
accepted, temporary state per the design doc).

- [ ] **Step 6: Report back**

Confirm with the user whether all of the above look correct before merging.
