# pkpass Localization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render pkpass field text, organization/subtitle, auto-generated title, and logo/strip images in whichever language the pass's bundled `.lproj` folders best match the device's *current* locale — recomputed live on every read, no re-import needed.

**Architecture:** A new stateless `PkPassLocalization` helper (folder matching + `.strings` parsing) is applied as a live decorator inside `PassRepository.observeAll()`/`getById()` for pkpass-sourced passes, and inside `PassImageLoader.load()` for images. The database keeps storing the original untranslated `pass.json` content exactly as today; nothing is translated at import time. Two new columns (`description`, `titleCustomized`) let the title be recomputed live too, while protecting user-renamed titles from being overwritten by a language change.

**Tech Stack:** Kotlin, Room (migration `MIGRATION_3_4`), Robolectric/JUnit for tests, no new external dependencies.

---

### Task 1: Data model — `description`, `titleCustomized`, `computeTitle`, migration

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/domain/Pass.kt`
- Modify: `app/src/main/java/ch/bigli/passes/data/PassEntity.kt`
- Modify: `app/src/main/java/ch/bigli/passes/data/PassDatabase.kt`
- Modify: `app/src/main/java/ch/bigli/passes/PassApp.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration3Test.kt`
- Test: `app/src/test/java/ch/bigli/passes/domain/PassTest.kt` (add a `computeTitle` test)

- [ ] **Step 1: Write the failing test for `computeTitle`**

Add to `app/src/test/java/ch/bigli/passes/domain/PassTest.kt` (append inside the existing `PassTest` class, after the existing test):

```kotlin
    @Test fun `computeTitle prefers two primary field labels, joined by an arrow`() {
        val fields = listOf(
            PassField("ZRH", "Zurich", FieldPosition.PRIMARY),
            PassField("JFK", "New York", FieldPosition.PRIMARY),
        )
        assertEquals("ZRH → JFK", computeTitle(fields, description = "Boarding pass", organizationName = "SWISS"))
    }

    @Test fun `computeTitle uses the single primary field's value when there is only one`() {
        val fields = listOf(PassField("Type", "VIP", FieldPosition.PRIMARY))
        assertEquals("VIP", computeTitle(fields, description = "Event ticket", organizationName = "Acme"))
    }

    @Test fun `computeTitle falls back to description then organization then a default when there are no primary fields`() {
        assertEquals("Some description", computeTitle(emptyList(), description = "Some description", organizationName = "Acme"))
        assertEquals("Acme", computeTitle(emptyList(), description = null, organizationName = "Acme"))
        assertEquals("Pass", computeTitle(emptyList(), description = null, organizationName = null))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.domain.PassTest"`
Expected: FAIL — `computeTitle` is unresolved.

- [ ] **Step 3: Add `description`, `titleCustomized`, and `computeTitle` to `Pass.kt`**

Replace the full contents of `app/src/main/java/ch/bigli/passes/domain/Pass.kt`:

```kotlin
package ch.bigli.passes.domain

import kotlinx.serialization.Serializable
import java.time.Instant

enum class PassType { BOARDING, EVENT, LOYALTY, COUPON, GENERIC }
enum class SourceFormat { PKPASS, GOOGLE_JSON, PDF, MANUAL }
enum class BarcodeFormat { QR, PDF417, AZTEC, CODE128 }
enum class FieldPosition { HEADER, PRIMARY, SECONDARY, AUXILIARY }

@Serializable
data class PassField(val label: String, val value: String, val position: FieldPosition)

@Serializable
data class Barcode(val format: BarcodeFormat, val message: String, val altText: String?)

@Serializable
data class UpdateInfo(val webServiceUrl: String, val authToken: String, val serialNumber: String, val passTypeId: String)

data class Pass(
    val id: String,
    val type: PassType,
    val title: String,
    val subtitle: String?,
    val organization: String?,
    val description: String? = null,
    val bgColor: Long?,
    val fgColor: Long?,
    val fields: List<PassField>,
    val barcode: Barcode?,
    val relevantDate: Instant?,
    val expirationDate: Instant? = null,
    val rawFilePath: String,
    val sourceFormat: SourceFormat,
    val updateInfo: UpdateInfo?,
    val voided: Boolean = false,
    val lastModified: String? = null,
    val titleCustomized: Boolean = false,
) {
    /** True if the issuer declared this pass voided, or its static [expirationDate] has passed. */
    fun isVoidedOrExpired(): Boolean = voided || expirationDate?.isBefore(Instant.now()) == true
}

/**
 * The same title-selection rule used at import time and re-applied live (with translated
 * inputs) on every read for passes whose title hasn't been manually customized: prefer two
 * primary fields joined by an arrow, then a single primary field's value, then description,
 * then organization name, then a hardcoded default.
 */
fun computeTitle(fields: List<PassField>, description: String?, organizationName: String?): String {
    val primary = fields.filter { it.position == FieldPosition.PRIMARY }
    return when {
        primary.size >= 2 -> "${primary[0].label} → ${primary[1].label}"
        primary.isNotEmpty() -> primary[0].value
        else -> description ?: organizationName ?: "Pass"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.domain.PassTest"`
Expected: PASS (4 tests: the original one plus the 3 new `computeTitle` cases — the third test has 3 assertions in one `@Test`, that's intentional, they're tightly related).

- [ ] **Step 5: Add `description`/`titleCustomized` columns to `PassEntity`**

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
    val title: String,
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
    val titleCustomized: Boolean = false,
)

fun Pass.toEntity() = PassEntity(
    id = id,
    type = type.name,
    title = title,
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
    titleCustomized = titleCustomized,
)

fun PassEntity.toDomain() = Pass(
    id = id,
    type = PassType.valueOf(type),
    title = title,
    subtitle = subtitle,
    organization = organization,
    bgColor = bgColor,
    fgColor = fgColor,
    fields = json.decodeFromString(ListSerializer(PassField.serializer()), fieldsJson),
    barcode = barcodeJson?.let { json.decodeFromString(Barcode.serializer(), it) },
    relevantDate = relevantDateEpoch?.let { java.time.Instant.ofEpochMilli(it) },
    rawFilePath = rawFilePath,
    sourceFormat = SourceFormat.valueOf(sourceFormat),
    updateInfo = updateInfoJson?.let { json.decodeFromString(UpdateInfo.serializer(), it) },
    voided = voided,
    lastModified = lastModified,
    expirationDate = expirationDateEpoch?.let { java.time.Instant.ofEpochMilli(it) },
    description = description,
    titleCustomized = titleCustomized,
)
```

- [ ] **Step 6: Add `MIGRATION_3_4` and bump the database version**

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
 * re-translation). Existing rows get `description = NULL`, `titleCustomized = 0` — every
 * pre-existing pass is treated as "not customized," so its title starts being live-recomputed
 * in the current locale after this update, which is the desired behavior.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE passes ADD COLUMN description TEXT")
        db.execSQL("ALTER TABLE passes ADD COLUMN titleCustomized INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [PassEntity::class], version = 4, exportSchema = false)
abstract class PassDatabase : RoomDatabase() {
    abstract fun passDao(): PassDao
}
```

- [ ] **Step 7: Wire the new migration into `PassApp`**

In `app/src/main/java/ch/bigli/passes/PassApp.kt`, update the import and the `addMigrations` call:

```kotlin
import ch.bigli.passes.data.MIGRATION_1_2
import ch.bigli.passes.data.MIGRATION_2_3
import ch.bigli.passes.data.MIGRATION_3_4
```

```kotlin
        val db = Room.databaseBuilder(this, PassDatabase::class.java, "passes.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
```

- [ ] **Step 8: Write the failing migration test**

Create `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration3Test.kt`:

```kotlin
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        val stored = runBlocking { db4.passDao().getById("p1") }
        checkNotNull(stored)
        assertEquals("Old Pass", stored.title)
        assertNull(stored.description)
        assertEquals(false, stored.titleCustomized)
        db4.close()
    }
}
```

- [ ] **Step 9: Run all tests to verify everything passes**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (the new migration test plus everything already existing — none of the earlier tests reference `description`/`titleCustomized` yet, so they're unaffected by the new default-valued columns).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/domain/Pass.kt \
        app/src/main/java/ch/bigli/passes/data/PassEntity.kt \
        app/src/main/java/ch/bigli/passes/data/PassDatabase.kt \
        app/src/main/java/ch/bigli/passes/PassApp.kt \
        app/src/test/java/ch/bigli/passes/domain/PassTest.kt \
        app/src/test/java/ch/bigli/passes/data/PassDatabaseMigration3Test.kt
git commit -m "feat: add description/titleCustomized fields and computeTitle (v3->v4 migration)"
```

---

### Task 2: `PkPassImporter` — store `description`, fix primary-field label fallback, use `computeTitle`

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/importing/PkPassImporter.kt`
- Test: `app/src/test/java/ch/bigli/passes/importing/PkPassImporterTest.kt`

**Why the primary-field fallback needs to change:** the original inline title logic used
`primary[0].label ?: primary[0].value` (falling back to the *value*, e.g. an airport code, when
a primary field has no label) — but `PkField.toField()` (used to build the stored `fields` list)
falls back to the *key* instead (`label ?: key`), because that's the right default for fields
that are actually displayed with a label (header/secondary/auxiliary — primary fields are never
individually rendered elsewhere, only consumed by title computation; confirmed by grepping the
UI). Since `computeTitle` in Task 1 operates on the stored `List<PassField>`, not the raw
`PkField` list, primary fields need to use the *value* fallback specifically so a live-recomputed
title from the stored fields produces the exact same string the original algorithm would.

- [ ] **Step 1: Write the failing test for the primary-field fallback and stored `description`**

Add to `app/src/test/java/ch/bigli/passes/importing/PkPassImporterTest.kt` (append inside the
existing `PkPassImporterTest` class):

```kotlin
    @Test fun `stores the raw description for later live title recomputation`() {
        val passJson = """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.test",
              "serialNumber": "SN1",
              "teamIdentifier": "TEAM",
              "organizationName": "Acme",
              "description": "Some description",
              "generic": { "primaryFields": [] }
            }
        """.trimIndent()
        val pass = importer.import(buildPkPass(passJson), "/data/x.pkpass", "x.pkpass")
        assertEquals("Some description", pass.description)
    }

    @Test fun `a primary field with no label falls back to its value for both display and title`() {
        val passJson = """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.test",
              "serialNumber": "SN1",
              "teamIdentifier": "TEAM",
              "organizationName": "Acme",
              "description": "Some description",
              "boardingPass": {
                "primaryFields": [
                  { "key": "origin", "value": "ZRH" },
                  { "key": "dest", "value": "JFK" }
                ]
              }
            }
        """.trimIndent()
        val pass = importer.import(buildPkPass(passJson), "/data/x.pkpass", "x.pkpass")
        assertEquals("ZRH → JFK", pass.title)
        val primaryLabels = pass.fields.filter { it.position == FieldPosition.PRIMARY }.map { it.label }
        assertEquals(listOf("ZRH", "JFK"), primaryLabels)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.importing.PkPassImporterTest"`
Expected: FAIL — `pass.description` doesn't exist yet as an assertion target compiles fine
(field now exists from Task 1) but is `null`; the second test fails because primary field labels
currently default to `key` ("origin"/"dest"), not `value` ("ZRH"/"JFK"), so the title comes out as
`"origin → dest"` instead of `"ZRH → JFK"`.

- [ ] **Step 3: Update `PkPassImporter` to store `description` and fix the primary-field fallback**

In `app/src/main/java/ch/bigli/passes/importing/PkPassImporter.kt`, add the import and make
these three changes:

```kotlin
import ch.bigli.passes.domain.computeTitle
```

Replace the field-building and title block:

```kotlin
        val (type, structure) = resolveStructure(pj)
        val fields = buildList {
            structure?.headerFields?.forEach { add(it.toField(FieldPosition.HEADER)) }
            structure?.primaryFields?.forEach { add(it.toField(FieldPosition.PRIMARY)) }
            structure?.secondaryFields?.forEach { add(it.toField(FieldPosition.SECONDARY)) }
            structure?.auxiliaryFields?.forEach { add(it.toField(FieldPosition.AUXILIARY)) }
        }
        val title = computeTitle(fields, pj.description, pj.organizationName)
```

Replace the `Pass(...)` construction to add `description` (place it after `organization`, matching
`Pass.kt`'s field order):

```kotlin
        return Pass(
            id = UUID.randomUUID().toString(),
            type = type,
            title = title,
            subtitle = pj.organizationName,
            organization = pj.organizationName,
            description = pj.description,
            bgColor = parseColor(pj.backgroundColor),
            fgColor = parseColor(pj.foregroundColor),
            fields = fields,
            barcode = resolveBarcode(pj),
            relevantDate = pj.relevantDate?.let { runCatching { Instant.parse(it) }.getOrNull() },
            rawFilePath = rawFilePath,
            sourceFormat = SourceFormat.PKPASS,
            updateInfo = update,
            expirationDate = pj.expirationDate?.let { runCatching { Instant.parse(it) }.getOrNull() },
            voided = pj.voided ?: false,
        )
```

Replace the `toField` helper at the bottom of the file:

```kotlin
    private fun PkField.toField(pos: FieldPosition) = PassField(
        label = if (pos == FieldPosition.PRIMARY) (label ?: value) else (label ?: key),
        value = value,
        position = pos,
    )
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.importing.PkPassImporterTest"`
Expected: PASS (all tests in the file, including the pre-existing ones — the `sample.pkpass`
fixture's primary fields already have explicit labels, so the fallback change doesn't affect it).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/importing/PkPassImporter.kt \
        app/src/test/java/ch/bigli/passes/importing/PkPassImporterTest.kt
git commit -m "feat: store raw description, use shared computeTitle in PkPassImporter"
```

---

### Task 3: `PkPassLocalization` — folder matching, `.strings` parsing, translation

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/importing/PkPassLocalization.kt`
- Test: `app/src/test/java/ch/bigli/passes/importing/PkPassLocalizationTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/ch/bigli/passes/importing/PkPassLocalizationTest.kt`:

```kotlin
package ch.bigli.passes.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PkPassLocalizationTest {
    private fun buildZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun `matches the lproj folder for the given language, case-insensitively`() {
        val zip = buildZip(mapOf(
            "pass.json" to "{}".toByteArray(),
            "de.lproj/pass.strings" to "\"Gate\" = \"Tor\";".toByteArray(),
            "fr.lproj/pass.strings" to "\"Gate\" = \"Porte\";".toByteArray(),
        ))
        val localization = PkPassLocalization.forZip(zip, "DE")
        assertEquals("de.lproj", localization.folder)
        assertEquals("Tor", localization.translate("Gate"))
    }

    @Test fun `no matching folder leaves text untranslated and folder null`() {
        val zip = buildZip(mapOf(
            "pass.json" to "{}".toByteArray(),
            "fr.lproj/pass.strings" to "\"Gate\" = \"Porte\";".toByteArray(),
        ))
        val localization = PkPassLocalization.forZip(zip, "de")
        assertNull(localization.folder)
        assertEquals("Gate", localization.translate("Gate"))
    }

    @Test fun `a matched folder's untranslated string falls back to the original`() {
        val zip = buildZip(mapOf("de.lproj/pass.strings" to "\"Gate\" = \"Tor\";".toByteArray()))
        val localization = PkPassLocalization.forZip(zip, "de")
        assertEquals("Seat 4B", localization.translate("Seat 4B"))
    }

    @Test fun `translate passes null through unchanged`() {
        val localization = PkPassLocalization.forZip(buildZip(emptyMap()), "de")
        assertNull(localization.translate(null))
    }

    @Test fun `parses strings with line and block comments and escaped quotes`() {
        val strings = """
            // a leading comment
            "Gate" = "Tor"; /* trailing block comment */
            "Say \"Hi\"" = "Sag \"Hallo\"";
        """.trimIndent()
        val zip = buildZip(mapOf("de.lproj/pass.strings" to strings.toByteArray()))
        val localization = PkPassLocalization.forZip(zip, "de")
        assertEquals("Tor", localization.translate("Gate"))
        assertEquals("Sag \"Hallo\"", localization.translate("Say \"Hi\""))
    }

    @Test fun `parses UTF-16 with BOM strings files`() {
        val content = "\"Gate\" = \"Tor\";"
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + content.toByteArray(Charsets.UTF_16BE)
        val zip = buildZip(mapOf("de.lproj/pass.strings" to bytes))
        val localization = PkPassLocalization.forZip(zip, "de")
        assertEquals("Tor", localization.translate("Gate"))
    }

    @Test fun `imageEntryName prefers 3x then 2x then base within the matched folder`() {
        val zip = buildZip(mapOf(
            "de.lproj/logo@2x.png" to byteArrayOf(1),
            "de.lproj/logo.png" to byteArrayOf(2),
            "logo@3x.png" to byteArrayOf(3),
        ))
        val localization = PkPassLocalization.forZip(zip, "de")
        assertEquals("de.lproj/logo@2x.png", localization.imageEntryName("logo"))
        assertNull(localization.imageEntryName("strip"))
    }

    @Test fun `imageEntryName is null when there is no matched folder`() {
        val localization = PkPassLocalization.forZip(buildZip(emptyMap()), "de")
        assertNull(localization.imageEntryName("logo"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.importing.PkPassLocalizationTest"`
Expected: FAIL to compile — `PkPassLocalization` doesn't exist yet.

- [ ] **Step 3: Implement `PkPassLocalization`**

Create `app/src/main/java/ch/bigli/passes/importing/PkPassLocalization.kt`:

```kotlin
package ch.bigli.passes.importing

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Resolves Apple pkpass localization — `<lang>.lproj/pass.strings` translation tables and
 * per-language image overrides — against a target language, matched live at read time (not
 * baked in at import time) so a device language change takes effect on the next read without
 * needing to re-import the pass.
 *
 * Matching is language-only (e.g. device `de-CH` matches a `de.lproj` folder; there's no
 * separate region-specific preference) since real-world pkpass files essentially never ship
 * region-specific folders.
 */
class PkPassLocalization private constructor(
    private val translations: Map<String, String>,
    private val entryNames: Set<String>,
    val folder: String?,
) {
    /** Looks up [text] verbatim in the matched folder's translation table; falls back to [text] unchanged if absent (or if there's no matched folder), and passes null through as null. */
    fun translate(text: String?): String? = text?.let { translations[it] ?: it }

    /** Best `@3x`/`@2x`/base resolution for [baseName] inside the matched folder, or null if there's no folder or no override for it. */
    fun imageEntryName(baseName: String): String? {
        val f = folder ?: return null
        return listOf("$baseName@3x.png", "$baseName@2x.png", "$baseName.png")
            .map { "$f/$it" }
            .firstOrNull { it in entryNames }
    }

    companion object {
        private val LPROJ_ENTRY = Regex("""^([^/]+)\.lproj/.+$""")

        /** Builds a [PkPassLocalization] for a pkpass zip's raw [zipBytes], matched against [languageTag] (defaults to the device's current language). */
        fun forZip(zipBytes: ByteArray, languageTag: String = Locale.getDefault().language): PkPassLocalization {
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                    entry = zip.nextEntry
                }
            }
            val folder = entries.keys
                .mapNotNull { LPROJ_ENTRY.find(it)?.groupValues?.get(1) }
                .distinct()
                .firstOrNull { it.equals(languageTag, ignoreCase = true) }
            val translations = folder
                ?.let { entries["$it/pass.strings"] }
                ?.let { parseStrings(it) }
                .orEmpty()
            return PkPassLocalization(translations, entries.keys, folder)
        }

        /** Parses Apple's `.strings` format: `"KEY" = "VALUE";` entries, `//`/`/* */` comments, `\"`/`\\` escapes within quoted strings. */
        internal fun parseStrings(bytes: ByteArray): Map<String, String> {
            val text = decodeStringsBytes(bytes)
            val result = mutableMapOf<String, String>()
            var i = 0
            val n = text.length

            fun skipWhitespaceAndComments() {
                while (i < n) {
                    val c = text[i]
                    if (c.isWhitespace()) { i++; continue }
                    if (c == '/' && i + 1 < n && text[i + 1] == '/') {
                        i += 2
                        while (i < n && text[i] != '\n') i++
                        continue
                    }
                    if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                        i += 2
                        while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                        i = (i + 2).coerceAtMost(n)
                        continue
                    }
                    break
                }
            }

            fun readQuotedString(): String? {
                if (i >= n || text[i] != '"') return null
                i++
                val sb = StringBuilder()
                while (i < n && text[i] != '"') {
                    if (text[i] == '\\' && i + 1 < n) {
                        sb.append(text[i + 1])
                        i += 2
                    } else {
                        sb.append(text[i])
                        i++
                    }
                }
                if (i < n) i++ // closing quote
                return sb.toString()
            }

            while (true) {
                skipWhitespaceAndComments()
                if (i >= n) break
                val key = readQuotedString() ?: break
                skipWhitespaceAndComments()
                if (i >= n || text[i] != '=') break
                i++
                skipWhitespaceAndComments()
                val value = readQuotedString() ?: break
                result[key] = value
                skipWhitespaceAndComments()
                if (i < n && text[i] == ';') i++
            }
            return result
        }

        private fun decodeStringsBytes(bytes: ByteArray): String = when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            else -> bytes.toString(Charsets.UTF_8)
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.importing.PkPassLocalizationTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/importing/PkPassLocalization.kt \
        app/src/test/java/ch/bigli/passes/importing/PkPassLocalizationTest.kt
git commit -m "feat: add PkPassLocalization (lproj folder matching + .strings parsing)"
```

---

### Task 4: `PassRepository` — live-translation decorator, `titleCustomized` on rename

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`
- Modify: `app/src/main/java/ch/bigli/passes/data/PassDao.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassRepositoryLocalizationTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/ch/bigli/passes/data/PassRepositoryLocalizationTest.kt`:

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
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.data.PassRepositoryLocalizationTest"`
Expected: FAIL — no translation is applied yet (`loaded.organization` is `"Gate"` not `"Tor"` in
the German-locale test), and `titleCustomized` isn't set by `updateTitle` yet.

- [ ] **Step 3: Add `titleCustomized` to the `updateTitle` query**

In `app/src/main/java/ch/bigli/passes/data/PassDao.kt`, replace the `updateTitle` query:

```kotlin
    @Query("UPDATE passes SET title = :title, titleCustomized = 1 WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)
```

- [ ] **Step 4: Add the live-translation decorator to `PassRepository`**

In `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`, add the import:

```kotlin
import ch.bigli.passes.domain.computeTitle
import ch.bigli.passes.importing.PkPassLocalization
```

Replace `observeAll()` and `getById()`:

```kotlin
    fun observeAll(): Flow<List<Pass>> = dao.observeAll().map { list -> list.map { localize(it.toDomain()) } }

    suspend fun getById(id: String): Pass? = withContext(Dispatchers.IO) { dao.getById(id)?.toDomain()?.let { localize(it) } }
```

Add the new private function (place it right after `getById`, before `delete`):

```kotlin
    /**
     * Applies live pkpass localization: re-reads the pass's raw zip (still on disk, unmodified)
     * and translates field labels/values, organization, subtitle, and description against
     * whatever language `Locale.getDefault()` currently returns — never baked in at import time,
     * so a device language change takes effect on the next read without a re-import. Title is
     * recomputed from the translated inputs too, unless the user has manually renamed the pass
     * ([Pass.titleCustomized]). Non-pkpass passes, and any pass whose raw file can't be read,
     * pass through unchanged.
     */
    private fun localize(pass: Pass): Pass {
        if (pass.sourceFormat != SourceFormat.PKPASS) return pass
        val bytes = runCatching { File(pass.rawFilePath).readBytes() }.getOrNull() ?: return pass
        val localization = runCatching { PkPassLocalization.forZip(bytes) }.getOrNull() ?: return pass

        val translatedFields = pass.fields.map {
            it.copy(
                label = localization.translate(it.label) ?: it.label,
                value = localization.translate(it.value) ?: it.value,
            )
        }
        val translatedOrganization = localization.translate(pass.organization)
        val translatedSubtitle = localization.translate(pass.subtitle)
        val translatedDescription = localization.translate(pass.description)
        val title = if (pass.titleCustomized) {
            pass.title
        } else {
            computeTitle(translatedFields, translatedDescription, translatedOrganization)
        }

        return pass.copy(
            fields = translatedFields,
            organization = translatedOrganization,
            subtitle = translatedSubtitle,
            description = translatedDescription,
            title = title,
        )
    }
```

Also update `refreshPass`'s `merged` construction to preserve `titleCustomized` across a refresh
(otherwise a customized title would silently lose its protection on the next refresh):

```kotlin
                    val merged = fresh.copy(
                        id = pass.id,
                        title = pass.title,
                        // Deliberately NOT forced to false: trust whatever the freshly re-imported
                        // pass.json says, so an issuer that still declares the pass voided stays voided.
                        voided = fresh.voided,
                        lastModified = conn.getHeaderField("Last-Modified") ?: pass.lastModified,
                        titleCustomized = pass.titleCustomized,
                    )
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.data.PassRepositoryLocalizationTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. In particular, confirm `PassRepositoryRefreshTest`,
`PassRepositoryUpdateTest`, and `PassRepositoryManualTest` all still pass unchanged — none of
their fixtures include `.lproj` folders, so `localize()` is a no-op for them except recomputing
an (identical) auto-generated title.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/data/PassRepository.kt \
        app/src/main/java/ch/bigli/passes/data/PassDao.kt \
        app/src/test/java/ch/bigli/passes/data/PassRepositoryLocalizationTest.kt
git commit -m "feat: live-translate pkpass content on read; mark titleCustomized on rename"
```

---

### Task 5: `PassImageLoader` — locale-aware image resolution

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/images/PassImageLoader.kt`
- Test: `app/src/test/java/ch/bigli/passes/images/PassImageLoaderTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/ch/bigli/passes/images/PassImageLoaderTest.kt` — add the imports and
the new test (append inside the existing `PassImageLoaderTest` class):

```kotlin
import android.graphics.Bitmap
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
```

```kotlin
    private fun pngBytes(width: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test fun `prefers a localized image override when the current locale matches`() = runTest {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("de"))
            val out = java.io.ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("logo.png")); zip.write(pngBytes(1)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("de.lproj/logo.png")); zip.write(pngBytes(2)); zip.closeEntry()
            }
            val f = File.createTempFile("localized", ".pkpass")
            f.writeBytes(out.toByteArray())
            f.deleteOnExit()

            val bmp = loader.load(f.absolutePath, PassImage.LOGO)
            assertEquals(2, bmp!!.width)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test fun `falls back to the top-level image when the locale has no override`() = runTest {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fr"))
            val out = java.io.ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("logo.png")); zip.write(pngBytes(1)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("de.lproj/logo.png")); zip.write(pngBytes(2)); zip.closeEntry()
            }
            val f = File.createTempFile("localized", ".pkpass")
            f.writeBytes(out.toByteArray())
            f.deleteOnExit()

            val bmp = loader.load(f.absolutePath, PassImage.LOGO)
            assertEquals(1, bmp!!.width)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.images.PassImageLoaderTest"`
Expected: FAIL — the first new test fails (loads the top-level 1px image instead of the
localized 2px one); locale-aware resolution doesn't exist yet.

- [ ] **Step 3: Add locale-aware resolution to `PassImageLoader`**

Replace the full contents of `app/src/main/java/ch/bigli/passes/images/PassImageLoader.kt`:

```kotlin
package ch.bigli.passes.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import ch.bigli.passes.importing.PkPassLocalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

enum class PassImage(val baseName: String) { LOGO("logo"), STRIP("strip") }

/** Best available resolution for [baseName] among zip [names], preferring @3x then @2x then base. */
internal fun bestImageEntry(names: Set<String>, baseName: String): String? =
    listOf("$baseName@3x.png", "$baseName@2x.png", "$baseName.png").firstOrNull { it in names }

/**
 * Loads pkpass [PassImage]s on-demand from the stored raw `.pkpass` zip at a given file path,
 * decoded off the main thread and cached in memory. Prefers a `<lang>.lproj/`-localized image
 * matching the device's current language over the top-level one, live at load time (same
 * approach as [ch.bigli.passes.data.PassRepository]'s field-text localization). Returns null if
 * the image is absent or the file/zip is unreadable; never throws to the caller.
 */
class PassImageLoader {
    private val cache = LruCache<String, Bitmap>(16)

    suspend fun load(rawFilePath: String, image: PassImage): Bitmap? = withContext(Dispatchers.IO) {
        val locale = Locale.getDefault().language
        // Include mtime (invalidates on a refreshed/overwritten pkpass) and the current locale
        // (invalidates if the language changes mid-process) in the cache key.
        val key = "$rawFilePath#${image.baseName}#${File(rawFilePath).lastModified()}#$locale"
        cache.get(key)?.let { return@withContext it }
        val bitmap = runCatching {
            val localization = PkPassLocalization.forZip(File(rawFilePath).readBytes(), locale)
            ZipFile(rawFilePath).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                val entryName = localization.imageEntryName(image.baseName)?.takeIf { it in names }
                    ?: bestImageEntry(names, image.baseName)
                    ?: return@use null
                zip.getInputStream(zip.getEntry(entryName)).use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
        if (bitmap != null) cache.put(key, bitmap)
        bitmap
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "ch.bigli.passes.images.PassImageLoaderTest"`
Expected: PASS (6 tests: the 4 pre-existing plus the 2 new ones).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/images/PassImageLoader.kt \
        app/src/test/java/ch/bigli/passes/images/PassImageLoaderTest.kt
git commit -m "feat: prefer a locale-matched image override in PassImageLoader"
```

---

### Task 6: Device verification

Not a code task — manual checks on the real Pixel device before merging.

- [ ] **Step 1: Build and install**

Run: `./gradlew :app:installDebug`

- [ ] **Step 2: Verify existing passes are unaffected**

Open the app with your existing imported passes (from before this change). Confirm:
- No crash on launch (the v3→v4 migration runs cleanly on your real, populated database).
- Every existing pass still displays its title, fields, and images exactly as before (none of
  them bundle `.lproj` folders, so `localize()` should be a complete no-op for all of them).

- [ ] **Step 3: Verify live localization with a real multilingual pkpass, if you have one**

If you have (or can obtain) a real pkpass that bundles a `.lproj` folder matching a language you
can switch your device to:
- Import it, confirm it displays in whatever language your device is currently set to (or the
  pass's default/top-level text if no match).
- Change your device's system language (Settings → System → Languages) to one the pass supports.
- Reopen the app and the pass; confirm the field text/organization/title now render in the new
  language, and the logo/strip image updates too if the pass has a localized image override.
- Rename the pass (pencil icon) to a custom title, then switch the device language again; confirm
  the custom title does NOT change.

- [ ] **Step 4: Report back**

Confirm with the user whether all of the above look correct before merging.
