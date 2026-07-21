# Pass Wallet — Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import a `.pkpass` by tapping it in another app ("Open with") or sharing it to Passes — no manual `+` picker needed. On success, jump straight to the imported pass's detail screen.

**Architecture:** Add one shared entry point `PassRepository.importFromUri(uri)` that reads a content/file `Uri`'s bytes off the main thread and funnels through the existing `import(bytes, name)` pipeline (Phase 1). Both the `+` file picker and a new Activity intent handler call it. Navigation to the new pass is signalled through an app-scoped `MutableStateFlow<String?>` that the Compose `NavHost` observes.

**Tech Stack:** Same as Phase 1 (Kotlin, Compose, Room, Robolectric). No new dependencies.

**Environment:** System `java` is JDK 26 which Gradle 8.9 rejects — every gradle command MUST be prefixed `JAVA_HOME=/opt/android-studio/jbr ./gradlew ...`. Base branch: `master`. Work on `feat/pass-wallet-phase2` (already checked out). All commit messages end with a real-newline trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

```
app/src/main/java/ch/bigli/passes/
    data/PassRepository.kt        MODIFY  + importFromUri(uri)
    PassApp.kt                    MODIFY  + pendingPassId StateFlow
    MainActivity.kt               MODIFY  intent handling + picker reuse + nav observer
    ui/PassListViewModel.kt       MODIFY  remove now-unused importBytes()
    src/main/AndroidManifest.xml  MODIFY  intent-filters + launchMode=singleTask
app/src/test/java/ch/bigli/passes/
    data/PassRepositoryTest.kt    MODIFY  + importFromUri tests
    ui/PassListViewModelTest.kt   MODIFY  drop importBytes error test
```

---

## Task 1: `PassRepository.importFromUri`

Single place that turns a `Uri` into an imported `Pass`. Reads bytes via `ContentResolver` on `Dispatchers.IO`, resolves a best-effort display name, and calls the existing `import(bytes, displayName)`.

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests** — append these two tests to the existing `PassRepositoryTest` class (before the closing brace). Also add the imports listed.

Add imports at top of the file:
```kotlin
import android.net.Uri
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
```

Add tests inside the class:
```kotlin
    @Test fun `importFromUri reads content uri bytes and persists`() = runTest {
        val uri = Uri.parse("content://test/sample.pkpass")
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(fixture("sample.pkpass")))
        val pass = repo.importFromUri(uri)
        assertEquals("SWISS", pass.organization)
        assertTrue(File(pass.rawFilePath).exists())
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test fun `importFromUri on non-pkpass throws UnsupportedFormat`() = runTest {
        val uri = Uri.parse("content://test/note.txt")
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream("hello world".toByteArray()))
        try {
            repo.importFromUri(uri)
            error("expected ImportError.UnsupportedFormat")
        } catch (e: Exception) {
            assertTrue(e is ImportError.UnsupportedFormat)
        }
    }
```
(NOTE: `ctx`, `repo`, `fixture`, and the `ImportError` import already exist in this test file from Phase 1. `runTest`, `File`, `first`, `assertEquals`, `assertTrue` are already imported.)

- [ ] **Step 2: Run tests → verify they FAIL** (importFromUri unresolved).

Run: `JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "*PassRepositoryTest*"`
Expected: compile failure, `Unresolved reference 'importFromUri'`.

- [ ] **Step 3: Implement** — add to `PassRepository.kt`. Add imports and the method.

Add imports at top (keep existing ones):
```kotlin
import android.net.Uri
import android.provider.OpenableColumns
```

Add this method inside the `PassRepository` class (e.g. right after `import(...)`):
```kotlin
    /**
     * Reads the bytes behind [uri] (a content:// or file:// document) off the main thread and
     * imports them through [import]. Used by the file picker and by "Open with"/share intents.
     */
    suspend fun importFromUri(uri: Uri): Pass = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImportError.CorruptFile("could not open $uri")
        import(bytes, displayName(uri))
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment ?: "pass.pkpass"
    }
```
(NOTE: `context`, `import`, `Dispatchers`, `withContext`, `Pass`, `ImportError` are already available in this file from Phase 1.)

- [ ] **Step 4: Run tests → verify PASS** (both new + the 2 existing repository tests).

Run: `JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "*PassRepositoryTest*"`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/data/PassRepository.kt app/src/test/java/ch/bigli/passes/data/PassRepositoryTest.kt
git commit -m "feat: add PassRepository.importFromUri for uri-based import"
```
Append the Co-Authored-By trailer.

---

## Task 2: App-scoped navigation signal + picker reuse

Add a `pendingPassId` signal to `PassApp`, route the `+` picker through `importFromUri`, and have the `NavHost` navigate to a pass when signalled. Remove the now-unused `PassListViewModel.importBytes`.

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/PassApp.kt`
- Modify: `app/src/main/java/ch/bigli/passes/MainActivity.kt`
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassListViewModel.kt`
- Test: `app/src/test/java/ch/bigli/passes/ui/PassListViewModelTest.kt`

- [ ] **Step 1: Add `pendingPassId` to `PassApp`** — edit `PassApp.kt`.

Add import:
```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
```
Add this property inside the `PassApp` class (after `repository`):
```kotlin
    /** Set to a pass id when an import should navigate to that pass's detail screen; NavHost observes and clears it. */
    val pendingPassId = MutableStateFlow<String?>(null)
```

- [ ] **Step 2: Simplify `PassListViewModel`** — remove `importBytes` (import now goes through the repository directly from `MainActivity`). Keep `passes`, `errors`, and `reportError`.

Delete this method from `PassListViewModel.kt`:
```kotlin
    fun importBytes(bytes: ByteArray, displayName: String) {
        viewModelScope.launch {
            try {
                repo.import(bytes, displayName)
            } catch (e: Exception) {
                _errors.emit(e.message ?: "Import failed")
            }
        }
    }
```
Then remove the now-unused import `import kotlinx.coroutines.launch` **only if** nothing else in the file uses `launch` (after deletion, nothing does — remove it). Keep `MutableSharedFlow`, `SharedFlow`, `stateIn`, etc. `reportError` uses `tryEmit`, not `launch`.

- [ ] **Step 3: Update the failing ViewModel test** — the old test `import error is surfaced on the error flow` called `importBytes`, which no longer exists. Replace that single test with one that exercises the surviving `reportError` path. In `PassListViewModelTest.kt`, replace the `import error is surfaced on the error flow` test with:
```kotlin
    @Test fun `reportError surfaces a message on the error flow`() = runTest {
        val vm = PassListViewModel(repo)
        vm.reportError("Unsupported format: note.txt")
        val err = vm.errors.first()
        assertTrue(err.contains("Unsupported", ignoreCase = true))
    }
```
Keep the `state reflects imported passes` test unchanged (it uses `repo.import`, still valid).

- [ ] **Step 4: Rewire the picker + add nav observer in `MainActivity`** — edit `MainActivity.kt`.

Replace the whole `composable("list") { ... }` block's picker wiring so the picker calls `repo.importFromUri` and signals navigation, and add a `LaunchedEffect` that observes `pendingPassId`. The `list` composable becomes:
```kotlin
        composable("list") {
            val vm: PassListViewModel = viewModel(factory = VmFactory { PassListViewModel(repo) })
            val scope = rememberCoroutineScope()
            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        try {
                            val pass = repo.importFromUri(uri)
                            app.pendingPassId.value = pass.id
                        } catch (e: Exception) {
                            vm.reportError(e.message ?: "Import failed")
                        }
                    }
                }
            }
            PassListScreen(
                viewModel = vm,
                onImportClick = { picker.launch(arrayOf("*/*")) },
                onPassClick = { id -> nav.navigate("detail/$id") },
            )
        }
```
This requires `AppNav` to receive the `PassApp` instance (for `app.pendingPassId`). Change `AppNav`'s signature and the observer. Full updated `MainActivity.kt`:
```kotlin
package ch.bigli.passes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.ui.PassDetailScreen
import ch.bigli.passes.ui.PassDetailViewModel
import ch.bigli.passes.ui.PassListScreen
import ch.bigli.passes.ui.PassListViewModel
import ch.bigli.passes.ui.theme.PassesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PassApp
        setContent { PassesTheme { AppNav(app) } }
        handleIncomingPass(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingPass(intent)
    }

    /** Extracts a .pkpass Uri from a VIEW or SEND intent and imports it, signalling navigation on success. */
    private fun handleIncomingPass(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM))
            else -> null
        }
        if (uri == null) return
        val app = application as PassApp
        lifecycleScope.launch {
            try {
                val pass = app.repository.importFromUri(uri)
                app.pendingPassId.value = pass.id
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private class VmFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

@Composable
private fun AppNav(app: PassApp) {
    val repo: PassRepository = app.repository
    val nav = rememberNavController()

    val pending by app.pendingPassId.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { id ->
            nav.navigate("detail/$id")
            app.pendingPassId.value = null
        }
    }

    NavHost(nav, startDestination = "list") {
        composable("list") {
            val vm: PassListViewModel = viewModel(factory = VmFactory { PassListViewModel(repo) })
            val scope = rememberCoroutineScope()
            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        try {
                            val pass = repo.importFromUri(uri)
                            app.pendingPassId.value = pass.id
                        } catch (e: Exception) {
                            vm.reportError(e.message ?: "Import failed")
                        }
                    }
                }
            }
            PassListScreen(
                viewModel = vm,
                onImportClick = { picker.launch(arrayOf("*/*")) },
                onPassClick = { id -> nav.navigate("detail/$id") },
            )
        }
        composable(
            "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("id")!!
            val vm: PassDetailViewModel = viewModel(factory = VmFactory { PassDetailViewModel(repo, id) })
            PassDetailScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
    }
}
```
NOTE: this drops the previous `LocalContext`-based inline byte read entirely (that logic now lives in `repo.importFromUri`). `PassListViewModel.importBytes` is gone; the picker uses `repo.importFromUri`. Both picker and intent paths set `pendingPassId`, so both auto-navigate to the imported pass.

- [ ] **Step 5: Build + run tests.**

Run: `JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Fix any unused-import errors (e.g. if the compiler flags a removed symbol).
Run: `JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (repository 4, viewmodel 2, plus Phase 1 suite).

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/PassApp.kt app/src/main/java/ch/bigli/passes/MainActivity.kt app/src/main/java/ch/bigli/passes/ui/PassListViewModel.kt app/src/test/java/ch/bigli/passes/ui/PassListViewModelTest.kt
git commit -m "feat: centralize import through importFromUri and auto-navigate to imported pass"
```
Append the Co-Authored-By trailer.

---

## Task 3: Manifest intent-filters + singleTask

Register the app to receive `.pkpass` files via "Open with" and Share.

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Edit the manifest.** Set `launchMode="singleTask"` on the activity (so an already-running app reuses its instance via `onNewIntent`), and add three intent-filters. Replace the `<activity>` element with:
```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- Open with: providers that report the correct pkpass MIME type -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:mimeType="application/vnd.apple.pkpass" />
            </intent-filter>

            <!-- Open with: file:// URIs matched by .pkpass extension (providers that report octet-stream) -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="file" />
                <data android:scheme="content" />
                <data android:host="*" />
                <data android:mimeType="application/octet-stream" />
                <data android:pathPattern=".*\\.pkpass" />
            </intent-filter>

            <!-- Share sheet -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/vnd.apple.pkpass" />
            </intent-filter>
        </activity>
```
NOTE: `pathPattern` only reliably filters `file://` URIs (content:// URIs have no meaningful path), which is why the primary MIME filter exists for well-behaved providers. This dual approach catches the common cases without registering Passes as a handler for *every* octet-stream file.

- [ ] **Step 2: Build to verify the manifest is valid.**

Run: `JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**
```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: register .pkpass open-with and share intent-filters"
```
Append the Co-Authored-By trailer.

---

## Task 4: On-device verification

Manual verification on the connected device (`JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:installDebug`; ADB at `/home/bigli/Android/Sdk/platform-tools/adb`). Not an automated test — the controller drives this.

- [ ] **Step 1: Install** the debug build to the device.
- [ ] **Step 2: "Open with" flow** — push a fixture and open it via a VIEW intent:
```bash
ADB=/home/bigli/Android/Sdk/platform-tools/adb
$ADB push app/src/test/resources/fixtures/sample.pkpass /sdcard/Download/sample.pkpass
$ADB shell am start -a android.intent.action.VIEW -d file:///sdcard/Download/sample.pkpass -t application/vnd.apple.pkpass -n ch.bigli.passes/.MainActivity
```
Expected: app opens directly on the SWISS "ZRH → JFK" detail screen with the QR code (auto-navigated).
- [ ] **Step 3: Cold vs warm** — force-stop (`$ADB shell am force-stop ch.bigli.passes`), repeat the VIEW intent (cold start lands on detail). Then with the app open, fire the intent again (warm — `onNewIntent` should navigate to detail without stacking a second task).
- [ ] **Step 4: Picker still works** — launch normally, tap `+`, pick the pushed file, confirm it imports and auto-navigates to detail.
- [ ] **Step 5: Clean up** the pushed fixture from the device (`$ADB shell rm /sdcard/Download/sample.pkpass`).
- [ ] **Step 6: Commit** any fixes made during verification (if none, skip).

---

## Task 5: Download-and-import (`importFromUrl` + walletpasses:// link parsing)

Support the web "Add to Wallet" flow: a `walletpasses://import/<url-encoded https url>` link is caught by the app, the real `.pkpass` URL is decoded, downloaded over HTTP(S), and fed into the existing import pipeline.

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/data/PassRepository.kt` (+ `importFromUrl`)
- Create: `app/src/main/java/ch/bigli/passes/importing/WalletPassesLink.kt` (pure Uri → https URL parser)
- Test: `app/src/test/java/ch/bigli/passes/importing/WalletPassesLinkTest.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassRepositoryUrlTest.kt`

- [ ] **Step 1: Write the failing parser test** — `app/src/test/java/ch/bigli/passes/importing/WalletPassesLinkTest.kt`
```kotlin
package ch.bigli.passes.importing

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletPassesLinkTest {
    @Test fun `decodes the encoded https target after import host`() {
        val uri = Uri.parse("walletpasses://import/https%3A%2F%2Fwalletpasses.io%2Fsample.pkpass")
        assertEquals("https://walletpasses.io/sample.pkpass", walletPassesTargetUrl(uri))
    }

    @Test fun `accepts http as well as https`() {
        val uri = Uri.parse("walletpasses://import/http%3A%2F%2Fexample.com%2Fp.pkpass")
        assertEquals("http://example.com/p.pkpass", walletPassesTargetUrl(uri))
    }

    @Test fun `rejects a non-http decoded target`() {
        val uri = Uri.parse("walletpasses://import/file%3A%2F%2F%2Fetc%2Fpasswd")
        assertNull(walletPassesTargetUrl(uri))
    }

    @Test fun `returns null when there is no path`() {
        assertNull(walletPassesTargetUrl(Uri.parse("walletpasses://import")))
    }
}
```

- [ ] **Step 2: Run → verify FAIL** (`walletPassesTargetUrl` unresolved).
`JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "*WalletPassesLinkTest*"`

- [ ] **Step 3: Implement the parser** — `app/src/main/java/ch/bigli/passes/importing/WalletPassesLink.kt`
```kotlin
package ch.bigli.passes.importing

import android.net.Uri
import java.net.URLDecoder

/**
 * Extracts the real pkpass download URL from a `walletpasses://import/<url-encoded https url>` link.
 * Returns null if the link has no encoded target or the decoded target is not an http(s) URL.
 */
fun walletPassesTargetUrl(uri: Uri): String? {
    val encoded = uri.encodedPath?.trimStart('/')?.takeIf { it.isNotBlank() } ?: return null
    val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
    return decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}
```

- [ ] **Step 4: Run → verify PASS** (4 parser tests).
`JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "*WalletPassesLinkTest*"`

- [ ] **Step 5: Write the failing download test** — `app/src/test/java/ch/bigli/passes/data/PassRepositoryUrlTest.kt`. Serves the fixture from an in-JVM HTTP server (no external network).
```kotlin
package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.importing.PkPassImporter
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
class PassRepositoryUrlTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository
    private lateinit var server: HttpServer
    private lateinit var base: String

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
        val bytes = fixture("sample.pkpass")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sample.pkpass") { ex ->
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/missing.pkpass") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun tearDown() { server.stop(0); db.close() }

    @Test fun `importFromUrl downloads and persists a pass`() = runTest {
        val pass = repo.importFromUrl("$base/sample.pkpass")
        assertEquals("SWISS", pass.organization)
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test fun `importFromUrl throws on http error`() = runTest {
        try {
            repo.importFromUrl("$base/missing.pkpass")
            error("expected ImportError")
        } catch (e: Exception) {
            assertTrue(e is ImportError)
        }
    }
}
```

- [ ] **Step 6: Run → verify FAIL** (`importFromUrl` unresolved).
`JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "*PassRepositoryUrlTest*"`

- [ ] **Step 7: Implement `importFromUrl`** — add to `PassRepository.kt`. Add imports:
```kotlin
import java.net.HttpURLConnection
import java.net.URL
```
Add this method inside the class (after `importFromUri`):
```kotlin
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
```

- [ ] **Step 8: Run → verify PASS** (both URL tests + existing repository tests unaffected).
`JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "*PassRepository*"`

- [ ] **Step 9: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/data/PassRepository.kt app/src/main/java/ch/bigli/passes/importing/WalletPassesLink.kt app/src/test/java/ch/bigli/passes/importing/WalletPassesLinkTest.kt app/src/test/java/ch/bigli/passes/data/PassRepositoryUrlTest.kt
git commit -m "feat: add importFromUrl and walletpasses:// link parsing"
```
Append the Co-Authored-By trailer.

---

## Task 6: Register walletpasses:// scheme + wire it up

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (INTERNET permission + scheme intent-filter)
- Modify: `app/src/main/java/ch/bigli/passes/MainActivity.kt` (route the scheme through `importFromUrl`)

- [ ] **Step 1: Manifest.** Add the INTERNET permission as a child of `<manifest>` (before `<application>`):
```xml
    <uses-permission android:name="android.permission.INTERNET" />
```
Add this intent-filter inside the `<activity android:name=".MainActivity">` element (alongside the existing filters):
```xml
            <!-- Web "Add to Wallet": walletpasses://import/<url-encoded https url> -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="walletpasses" />
            </intent-filter>
```

- [ ] **Step 2: Route the scheme in `MainActivity.handleIncomingPass`.** Replace the existing `handleIncomingPass` method with a version that branches on the `walletpasses` scheme before the content/file path. Add imports `import ch.bigli.passes.importing.walletPassesTargetUrl`. New method:
```kotlin
    /** Extracts a pass from a VIEW (file/content or walletpasses://) or SEND intent and imports it. */
    private fun handleIncomingPass(intent: Intent?) {
        val app = application as PassApp
        val action = intent?.action
        val viewUri: Uri? = if (action == Intent.ACTION_VIEW) intent.data else null

        if (viewUri?.scheme == "walletpasses") {
            val target = walletPassesTargetUrl(viewUri)
            if (target == null) {
                Toast.makeText(this, "Invalid pass link", Toast.LENGTH_LONG).show()
                return
            }
            lifecycleScope.launch {
                try {
                    app.pendingPassId.value = app.repository.importFromUrl(target).id
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        val uri: Uri? = when (action) {
            Intent.ACTION_VIEW -> viewUri
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM))
            else -> null
        }
        if (uri == null) return
        lifecycleScope.launch {
            try {
                app.pendingPassId.value = app.repository.importFromUri(uri).id
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }
```

- [ ] **Step 3: Build + full test suite.**
`JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
`JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest` → all pass.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/ch/bigli/passes/MainActivity.kt
git commit -m "feat: register walletpasses:// scheme and download passes from web links"
```
Append the Co-Authored-By trailer.

- [ ] **Step 5: On-device verification** (controller-driven). With a locally reachable pkpass, fire:
```bash
ADB=/home/bigli/Android/Sdk/platform-tools/adb
# encoded target points at a reachable https .pkpass; verify the app opens on the imported pass detail
$ADB shell am start -a android.intent.action.VIEW -d "walletpasses://import/<url-encoded-https-pkpass>" ch.bigli.passes/.MainActivity
```
Expected: app downloads the pass and lands on its detail screen. (If no public pkpass URL is handy, this step can be verified against a host-run HTTP server reachable from the device, or deferred to the user.)

---

## Self-Review notes

- **Spec coverage:** "Open with" VIEW intent (Task 3 filters + Task 2 handler), Share SEND intent (Task 3 + handler), reuse of existing import pipeline via `importFromUri` (Task 1), auto-navigate to imported pass (Task 2 `pendingPassId`), single-instance behaviour (Task 3 `singleTask` + `onNewIntent`).
- **DRY:** the picker and both intent actions share one code path (`PassRepository.importFromUri`); the inline byte-reading from Phase 1's picker is removed.
- **walletpasses:// (Tasks 5–6):** web "Add to Wallet" links (`walletpasses://import/<url-encoded https url>`) are parsed (`walletPassesTargetUrl`), the `.pkpass` is downloaded (`importFromUrl`, needs INTERNET permission), and funnelled through the same `import(bytes, name)` pipeline. Still `.pkpass` only.
- **Deferred (unchanged):** PDF/QR/Google importers (Phase 3), signature verification + auto-update (Phase 4). No new formats added here.
- **Type consistency:** `importFromUri(uri: Uri): Pass` returns the same `Pass` used everywhere; `pendingPassId: MutableStateFlow<String?>` holds a pass `id` matching the `detail/{id}` nav route.
