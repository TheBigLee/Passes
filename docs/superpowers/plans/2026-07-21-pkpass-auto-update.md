# pkpass Auto-Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep imported pkpass passes fresh by polling each pass's `webServiceURL` (Apple PassKit web service protocol, without APNs push) — periodically in the background and on-demand via pull-to-refresh — handling changed/unchanged/voided responses.

**Architecture:** `Pass`/`PassEntity` gain `voided: Boolean` and `lastModified: String?`. A real Room migration (`MIGRATION_1_2`) adds the columns without wiping existing data. `PassRepository.refreshPass(id)` does the actual HTTP fetch (reusing the existing plain-`HttpURLConnection` style) and returns a `RefreshResult`. A `WorkManager` `CoroutineWorker` calls it for every eligible pass every 6 hours; the pass detail screen calls it directly via pull-to-refresh.

**Tech Stack:** Kotlin, Room 2.8.4 (real migration, no `MigrationTestHelper`), WorkManager 2.11.2, Jetpack Compose Material3 (`PullToRefreshBox`), Robolectric.

**Environment:** Run `./gradlew <task>` BARE (daemon toolchain provides JDK 21). Branch: `feat/pkpass-auto-update` (already checked out). Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Device verification: build + install, then hand to the user — no `adb screencap`/`adb input`.

**Verified ahead of time:** the migration mechanics below (hand-built v1 SQLite file + `PRAGMA user_version = 1` + `Room.databaseBuilder(...).addMigrations(MIGRATION_1_2)`) were confirmed working in a throwaway scratch test before writing this plan — no `MigrationTestHelper`/schema-export setup is needed, a plain `Migration` object is sufficient.

---

## Task 1: Data model + Room migration (`voided`, `lastModified`)

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/domain/Pass.kt`
- Modify: `app/src/main/java/ch/bigli/passes/data/PassEntity.kt`
- Modify: `app/src/main/java/ch/bigli/passes/data/PassDao.kt`
- Modify: `app/src/main/java/ch/bigli/passes/data/PassDatabase.kt`
- Modify: `app/src/main/java/ch/bigli/passes/PassApp.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigrationTest.kt`

- [ ] **Step 1: Failing test** — create `app/src/test/java/ch/bigli/passes/data/PassDatabaseMigrationTest.kt`:
```kotlin
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
```

- [ ] **Step 2: Run → FAIL.** `./gradlew :app:testDebugUnitTest --tests "*PassDatabaseMigrationTest*"`
Expected failure: compile error (`MIGRATION_1_2` unresolved, `PassEntity.voided`/`lastModified` unresolved) or a runtime crash if it somehow compiles — either way it must not pass yet.

- [ ] **Step 3: Add the fields to the domain model.** In `app/src/main/java/ch/bigli/passes/domain/Pass.kt`, add two fields with defaults to `Pass` (defaults mean no other call site — `PkPassImporter`, `createManualPass`, existing tests — needs to change):
```kotlin
data class Pass(
    val id: String,
    val type: PassType,
    val title: String,
    val subtitle: String?,
    val organization: String?,
    val bgColor: Long?,
    val fgColor: Long?,
    val fields: List<PassField>,
    val barcode: Barcode?,
    val relevantDate: Instant?,
    val rawFilePath: String,
    val sourceFormat: SourceFormat,
    val updateInfo: UpdateInfo?,
    val voided: Boolean = false,
    val lastModified: String? = null,
)
```
(Only the last two lines are new; keep every existing field exactly as-is.)

- [ ] **Step 4: Add matching entity columns.** In `app/src/main/java/ch/bigli/passes/data/PassEntity.kt`, add the two columns (with defaults, so nothing else constructing `PassEntity` needs to change) and wire them in both mapping functions:
```kotlin
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
)
```
In `Pass.toEntity()`, add after `updateInfoJson = ...`:
```kotlin
    voided = voided,
    lastModified = lastModified,
```
In `PassEntity.toDomain()`, add after `updateInfo = ...`:
```kotlin
    voided = voided,
    lastModified = lastModified,
```

- [ ] **Step 5: Add a DAO query to mark a pass voided.** In `app/src/main/java/ch/bigli/passes/data/PassDao.kt`, add after `updateTitle`:
```kotlin
    @Query("UPDATE passes SET voided = 1 WHERE id = :id")
    suspend fun markVoided(id: String)
```

- [ ] **Step 6: Add the migration and bump the schema version.** Replace the full contents of `app/src/main/java/ch/bigli/passes/data/PassDatabase.kt`:
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

@Database(entities = [PassEntity::class], version = 2, exportSchema = false)
abstract class PassDatabase : RoomDatabase() {
    abstract fun passDao(): PassDao
}
```
IMPORTANT: this app has real installed users with live data — do **not** use `fallbackToDestructiveMigration()`. The whole point of this task is a real migration that preserves existing rows.

- [ ] **Step 7: Wire the migration into the database builder.** In `app/src/main/java/ch/bigli/passes/PassApp.kt`, change:
```kotlin
        val db = Room.databaseBuilder(this, PassDatabase::class.java, "passes.db").build()
```
to:
```kotlin
        val db = Room.databaseBuilder(this, PassDatabase::class.java, "passes.db")
            .addMigrations(MIGRATION_1_2)
            .build()
```
Add the import: `import ch.bigli.passes.data.MIGRATION_1_2`.

- [ ] **Step 8: Run → PASS.** `./gradlew :app:testDebugUnitTest --tests "*PassDatabaseMigrationTest*"`

- [ ] **Step 9: Full regression.** `./gradlew :app:testDebugUnitTest` → all existing tests still pass (they all use `Room.inMemoryDatabaseBuilder`, which always starts fresh at the target version — no migration path is exercised by them, so they're unaffected by this change).

- [ ] **Step 10: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/domain/Pass.kt \
        app/src/main/java/ch/bigli/passes/data/PassEntity.kt \
        app/src/main/java/ch/bigli/passes/data/PassDao.kt \
        app/src/main/java/ch/bigli/passes/data/PassDatabase.kt \
        app/src/main/java/ch/bigli/passes/PassApp.kt \
        app/src/test/java/ch/bigli/passes/data/PassDatabaseMigrationTest.kt
git commit -m "$(cat <<'EOF'
feat: add voided/lastModified fields with a real v1->v2 Room migration

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `PassRepository.refreshPass` + shared `TestHttpServer`

**Files:**
- Create: `app/src/test/java/ch/bigli/passes/data/TestHttpServer.kt`
- Modify: `app/src/test/java/ch/bigli/passes/data/PassRepositoryUrlTest.kt` (use the extracted server)
- Modify: `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassRepositoryRefreshTest.kt`

- [ ] **Step 1: Extract the shared test HTTP server, with header support.** `PassRepositoryUrlTest.kt` currently has a private `TestHttpServer` class that only serves a fixed status/body per path and ignores request headers entirely. This task needs a server that can inspect the `If-Modified-Since` request header and send custom response headers (`Last-Modified`), so extract it into its own file, upgraded, and used by both test classes (DRY — avoids two copies drifting apart).

Create `app/src/test/java/ch/bigli/passes/data/TestHttpServer.kt`:
```kotlin
package ch.bigli.passes.data

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * A minimal single-request-at-a-time HTTP/1.0 server backed by [ServerSocket], shared by tests
 * that exercise [PassRepository]'s HTTP paths (download-and-import, update polling).
 *
 * [com.sun.net.httpserver.HttpServer] would be the obvious choice here, but it lives in the
 * `jdk.httpserver` JDK module, which is not part of Android's `android.jar` stub that Kotlin
 * compiles unit tests against (Robolectric tests run on the real host JVM, but they are still
 * *compiled* against android.jar). Referencing `com.sun.net.httpserver.*` therefore fails with
 * "Unresolved reference" at compile time even though it would resolve fine at runtime. Plain
 * `java.net.ServerSocket`/`Socket` are part of Android's public API surface and compile cleanly.
 */
internal class TestHttpServer : AutoCloseable {
    data class Response(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    private val serverSocket = ServerSocket(0)
    private val routes = mutableMapOf<String, (Map<String, String>) -> Response>()
    private val lastRequestHeaders = mutableMapOf<String, Map<String, String>>()
    @Volatile private var running = true
    private val thread = Thread {
        while (running) {
            val socket = try {
                serverSocket.accept()
            } catch (e: Exception) {
                break
            }
            handle(socket)
        }
    }.apply { isDaemon = true }

    val port: Int get() = serverSocket.localPort

    /** Always responds with the same fixed status/body/headers. */
    fun respond(path: String, status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        routes[path] = { Response(status, body, headers) }
    }

    /** Registers a handler that can inspect request headers (e.g. to honor If-Modified-Since). */
    fun respond(path: String, handler: (requestHeaders: Map<String, String>) -> Response) {
        routes[path] = handler
    }

    /** The headers of the most recent request received for [path], or null if none yet. */
    fun headersReceived(path: String): Map<String, String>? = lastRequestHeaders[path]

    fun start() {
        thread.start()
    }

    private fun handle(socket: Socket) {
        socket.use {
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: return
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                line = reader.readLine()
            }
            lastRequestHeaders[path] = headers
            val response = routes[path]?.invoke(headers) ?: Response(404, ByteArray(0))
            val out: OutputStream = socket.getOutputStream()
            val statusText = if (response.status in 200..299) "OK" else "Error"
            out.write("HTTP/1.0 ${response.status} $statusText\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write("Content-Length: ${response.body.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            response.headers.forEach { (k, v) -> out.write("$k: $v\r\n".toByteArray(StandardCharsets.ISO_8859_1)) }
            out.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write(response.body)
            out.flush()
        }
    }

    override fun close() {
        running = false
        runCatching { serverSocket.close() }
    }
}
```

- [ ] **Step 2: Remove the now-duplicate private `TestHttpServer` from `PassRepositoryUrlTest.kt`.** Delete the entire private `TestHttpServer` class from that file (lines defining `private class TestHttpServer : AutoCloseable { ... }`), keeping the rest of the file (the `@RunWith(RobolectricTestRunner::class) class PassRepositoryUrlTest { ... }` body) unchanged — it already calls `server.respond("/sample.pkpass", 200, bytes)` and `server.respond("/missing.pkpass", 404, ByteArray(0))`, which match the new shared server's signature exactly (same package, so no import needed). Run `./gradlew :app:testDebugUnitTest --tests "*PassRepositoryUrlTest*"` → still passes, unchanged behavior.

- [ ] **Step 3: Failing test for the refresh protocol.** Create `app/src/test/java/ch/bigli/passes/data/PassRepositoryRefreshTest.kt`:
```kotlin
package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
    private fun buildPkPass(webServiceUrl: String, organizationName: String = "Acme", serial: String = "SN1"): ByteArray {
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

    @Test fun `refreshPass on a pass without updateInfo returns NotUpdatable without a network call`() = runTest {
        val manual = repo.createManualPass("Coop card", BarcodeFormat.CODE128, "6001234567890")
        val result = repo.refreshPass(manual.id)
        assertEquals(RefreshResult.NotUpdatable, result)
    }
}
```

- [ ] **Step 4: Run → FAIL.** `./gradlew :app:testDebugUnitTest --tests "*PassRepositoryRefreshTest*"`
Expected: compile error (`RefreshResult`, `PassRepository.refreshPass` unresolved).

- [ ] **Step 5: Implement `RefreshResult` and `refreshPass`.** In `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`, add the sealed result type at the top of the file (after the `import` block, before the `class PassRepository` declaration):
```kotlin
sealed interface RefreshResult {
    data class Updated(val pass: Pass) : RefreshResult
    data object Unchanged : RefreshResult
    data object Voided : RefreshResult
    data object NotUpdatable : RefreshResult
    data class Error(val message: String) : RefreshResult
}
```
Add the method inside the `PassRepository` class (e.g. after `importFromUrl`):
```kotlin
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
                        pkPassImporter.import(bytes, pass.rawFilePath, pass.title)
                    } catch (e: Exception) {
                        return@withContext RefreshResult.Error("malformed update: ${e.message}")
                    }
                    File(pass.rawFilePath).writeBytes(bytes)
                    val merged = fresh.copy(
                        id = pass.id,
                        title = pass.title,
                        voided = false,
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
```
No new imports are needed — `HttpURLConnection`, `URL`, `File`, `SourceFormat`, `Dispatchers`, `withContext` are already imported in this file.

- [ ] **Step 6: Run → PASS.** `./gradlew :app:testDebugUnitTest --tests "*PassRepositoryRefreshTest*"`

- [ ] **Step 7: Full regression.** `./gradlew :app:testDebugUnitTest` → all pass, including the refactored `PassRepositoryUrlTest`.

- [ ] **Step 8: Commit**
```bash
git add app/src/test/java/ch/bigli/passes/data/TestHttpServer.kt \
        app/src/test/java/ch/bigli/passes/data/PassRepositoryUrlTest.kt \
        app/src/main/java/ch/bigli/passes/data/PassRepository.kt \
        app/src/test/java/ch/bigli/passes/data/PassRepositoryRefreshTest.kt
git commit -m "$(cat <<'EOF'
feat: add PassRepository.refreshPass (pkpass update polling)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Background scheduling (`WorkManager`)

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `app/src/main/java/ch/bigli/passes/update/PassUpdateWorker.kt`
- Modify: `app/src/main/java/ch/bigli/passes/PassApp.kt`

No unit test — this is a thin iteration wrapper over `refreshPass`, which Task 2 already covers. Verified indirectly by Task 5's device check and by the build succeeding.

- [ ] **Step 1: Add WorkManager to the version catalog.** In `gradle/libs.versions.toml`, add under `[versions]`:
```toml
workmanager = "2.11.2"
```
and under `[libraries]`:
```toml
androidx-work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "workmanager" }
```

- [ ] **Step 2: Add the dependency.** In `app/build.gradle.kts`, add inside `dependencies { }`:
```kotlin
    implementation(libs.androidx.work.runtime.ktx)
```

- [ ] **Step 3: The worker.** Create `app/src/main/java/ch/bigli/passes/update/PassUpdateWorker.kt`:
```kotlin
package ch.bigli.passes.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.bigli.passes.PassApp
import ch.bigli.passes.domain.SourceFormat
import kotlinx.coroutines.flow.first

/**
 * Periodically re-fetches every pkpass that carries update info, so gate/seat/balance changes
 * surface without a manual refresh. Per-pass failures are swallowed (logged via [runCatching])
 * so one bad pass doesn't stop the rest from being checked; they're simply retried next cycle.
 */
class PassUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as PassApp).repository
        val passes = repo.observeAll().first()
        passes
            .filter { it.sourceFormat == SourceFormat.PKPASS && it.updateInfo != null && !it.voided }
            .forEach { runCatching { repo.refreshPass(it.id) } }
        return Result.success()
    }
}
```

- [ ] **Step 4: Schedule it.** In `app/src/main/java/ch/bigli/passes/PassApp.kt`, add imports:
```kotlin
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ch.bigli.passes.update.PassUpdateWorker
import java.util.concurrent.TimeUnit
```
At the end of `onCreate()` (after `repository = PassRepository(...)`), add:
```kotlin
        val updateRequest = PeriodicWorkRequestBuilder<PassUpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "pass-update-check", ExistingPeriodicWorkPolicy.KEEP, updateRequest,
        )
```

- [ ] **Step 5: Build + full regression.**
`./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
`./gradlew :app:testDebugUnitTest` → all pass.

- [ ] **Step 6: Commit**
```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/ch/bigli/passes/update/PassUpdateWorker.kt \
        app/src/main/java/ch/bigli/passes/PassApp.kt
git commit -m "$(cat <<'EOF'
feat: schedule a periodic background pass-update check (WorkManager)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Pull-to-refresh + voided UI

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassDetailViewModel.kt`
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt`
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt`

No unit test for the Compose UI itself (gesture/animation); `PassDetailViewModel.refresh()`'s logic is a thin wrapper over the already-tested `refreshPass`. Verified on device in Task 5.

- [ ] **Step 1: `PassDetailViewModel.refresh()`.** In `app/src/main/java/ch/bigli/passes/ui/PassDetailViewModel.kt`, add imports:
```kotlin
import ch.bigli.passes.data.RefreshResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
```
(`MutableStateFlow`/`StateFlow` are already imported.) Add inside the class:
```kotlin
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _refreshMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val refreshMessage: SharedFlow<String> = _refreshMessage

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
```

- [ ] **Step 2: Wire pull-to-refresh + the voided banner + a snackbar into `PassDetailScreen`.** In `app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt`, add imports:
```kotlin
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
```
(`androidx.compose.foundation.layout.wrapContentSize` may not actually be needed — only add it if you use it; the banner below only needs `fillMaxWidth`/`padding`/`background`, all already imported.)

Add snackbar state and a message collector near the top of the composable body (after `var showEditDialog by rememberSaveable { ... }`):
```kotlin
    val snackbar = remember { SnackbarHostState() }
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refreshMessage.collect { snackbar.showSnackbar(it) }
    }
```

Add `snackbarHost = { SnackbarHost(snackbar) }` to the `Scaffold(...)` call (alongside the existing `containerColor`/`topBar` parameters).

Wrap the existing body content in a `PullToRefreshBox` and add the voided banner as its first child. The current body is:
```kotlin
    ) { padding ->
        if (p == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding)) {
            strip?.let { ... }
            Column( ... ) { ... }
        }
    }
```
Change it to:
```kotlin
    ) { padding ->
        if (p == null) return@Scaffold
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (p.voided) {
                    Text(
                        "This pass has been voided by the issuer",
                        color = fg,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.25f))
                            .padding(8.dp),
                    )
                }
                strip?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // ...unchanged: Text(p.title, ...) through the barcode Column and both Spacers...
                }
            }
        }
    }
```
Keep every line inside the two nested `Column`s exactly as it is today — only the two wrapping layers (`PullToRefreshBox` and the voided `Text` banner) are new. `PullToRefreshBox` is part of the current compose-bom (Material3 1.3+) — no version bump needed. It requires `@OptIn(ExperimentalMaterial3Api::class)`, already present on this composable.

- [ ] **Step 3: Voided badge on the list.** In `app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt`, in the `PassCard` composable, add after the `Text(pass.title, ...)` line:
```kotlin
        if (pass.voided) {
            Text("VOIDED", color = fg.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
```

- [ ] **Step 4: Build + full regression.**
`./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. If `PullToRefreshBox` is unresolved, check the resolved Material3 artifact version via `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep material3`; it must be ≥1.3.0. Report if a version bump was needed (it shouldn't be, given the current compose-bom).
`./gradlew :app:testDebugUnitTest` → all pass.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/ui/PassDetailViewModel.kt \
        app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt \
        app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt
git commit -m "$(cat <<'EOF'
feat: add pull-to-refresh and a voided-pass banner/badge

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: On-device verification

- [ ] **Step 1: Install.** `./gradlew :app:installDebug`.
- [ ] **Step 2: Hand off to the user.** Ask them to confirm:
  - **No data loss:** existing passes (imported before this branch) are all still present and open correctly after the app updates — this is the migration working.
  - **Pull-to-refresh, unsupported pass:** open a manually-entered or PDF-imported pass, pull down → a brief "This pass can't be refreshed" snackbar, no crash.
  - **Pull-to-refresh, a real pkpass with `webServiceURL`:** pull down on a pkpass that has update info (if one is available) → either "Up to date" or "Pass updated", no crash. (A full round-trip against a *real* issuer's server can't be scripted here — this just confirms the UI path and that a genuine network round-trip doesn't crash.)
  - **List still renders correctly** — no voided badges appear on normal passes.
  Do NOT capture screenshots.
- [ ] **Step 3: Commit** any fixes (if none, skip).

---

## Self-Review notes

- **Spec coverage:** `voided`/`lastModified` fields + real migration (Task 1); `refreshPass` protocol — 200/304/410/error/not-updatable (Task 2); 6-hourly `WorkManager` background check (Task 3); pull-to-refresh + voided banner/badge (Task 4); device verification incl. no-data-loss check (Task 5).
- **Migration risk:** verified end-to-end before writing this plan (scratch Robolectric test against a hand-built v1 SQLite file), so Task 1's test is known-working, not speculative.
- **Type consistency:** `RefreshResult` (Updated/Unchanged/Voided/NotUpdatable/Error) used identically by `PassRepository.refreshPass`, `PassUpdateWorker`, and `PassDetailViewModel.refresh()`. `Pass.voided: Boolean`, `Pass.lastModified: String?` threaded through `PassEntity`, `PassDao.markVoided`, and the UI (`PassDetailScreen`, `PassListScreen`).
- **Deferred:** Apple signature/manifest verification; Google Wallet JSON update support; real APNs registration.
