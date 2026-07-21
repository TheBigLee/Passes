# Editable Pass Title — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user rename any pass from its detail screen, and auto-prompt for the title right after importing a PDF.

**Architecture:** One shared "rename" primitive (`PassDao.updateTitle` → `PassRepository.updateTitle` → `PassDetailViewModel.updateTitle`) surfaced by an edit-pencil + `AlertDialog` on the detail screen. The PDF-import case reuses that dialog: a `PendingPass(id, editTitle)` navigation signal carries an `editTitle` flag (true only for PDF-sourced imports) to the detail screen via a nav argument, which auto-opens the dialog pre-filled.

**Tech Stack:** Kotlin, Room, Jetpack Compose (Material 3), Robolectric.

**Environment:** Run `./gradlew <task>` BARE — no `JAVA_HOME` prefix. Branch: `feat/pdf-import` (already checked out; this builds on the PDF work). Commit messages end with a real-newline trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. **Device verification: build + install, then hand off to the user — do NOT run `adb screencap`/`adb input`.**

---

## File Structure

```
app/src/main/java/ch/bigli/passes/
    data/PassDao.kt                  MODIFY  + updateTitle query
    data/PassRepository.kt           MODIFY  + updateTitle (blank-safe)
    ui/PassDetailViewModel.kt        MODIFY  + updateTitle
    ui/PassDetailScreen.kt           MODIFY  edit pencil + rename dialog + openTitleEditor
    PassApp.kt                       MODIFY  pendingPassId -> pendingPass (PendingPass)
    MainActivity.kt                  MODIFY  set PendingPass; editTitle nav arg
app/src/test/java/ch/bigli/passes/
    data/PassRepositoryUpdateTest.kt NEW  updateTitle round-trip + blank-safe
    ui/PassDetailViewModelTest.kt    NEW  updateTitle reflects in pass
```

---

## Task 1: `updateTitle` in DAO + repository

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/data/PassDao.kt`
- Modify: `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassRepositoryUpdateTest.kt`

- [ ] **Step 1: Write the failing test** — `app/src/test/java/ch/bigli/passes/data/PassRepositoryUpdateTest.kt`
```kotlin
package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassRepositoryUpdateTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
    }

    @After fun tearDown() = db.close()

    @Test fun `updateTitle changes the stored title`() = runTest {
        val pass = repo.import(fixture("sample.pkpass"), "sample.pkpass")
        repo.updateTitle(pass.id, "My Trip")
        assertEquals("My Trip", repo.getById(pass.id)!!.title)
    }

    @Test fun `updateTitle ignores a blank title`() = runTest {
        val pass = repo.import(fixture("sample.pkpass"), "sample.pkpass")
        repo.updateTitle(pass.id, "My Trip")
        repo.updateTitle(pass.id, "   ")
        assertEquals("My Trip", repo.getById(pass.id)!!.title)
    }
}
```

- [ ] **Step 2: Run → verify FAIL** (`updateTitle` unresolved on repo).

Run: `./gradlew :app:testDebugUnitTest --tests "*PassRepositoryUpdateTest*"`

- [ ] **Step 3: Add the DAO query** — in `app/src/main/java/ch/bigli/passes/data/PassDao.kt`, add inside the `PassDao` interface (after `deleteById`):
```kotlin
    @Query("UPDATE passes SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)
```

- [ ] **Step 4: Add the repository method** — in `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`, add this method inside the class (e.g. right after `delete`):
```kotlin
    /** Renames a pass. A blank/whitespace-only title is ignored so a pass can't be left untitled. */
    suspend fun updateTitle(id: String, title: String) = withContext(Dispatchers.IO) {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) dao.updateTitle(id, trimmed)
    }
```
(`withContext`, `Dispatchers`, `dao` are already in scope in this file.)

- [ ] **Step 5: Run → verify PASS** (2 tests).

Run: `./gradlew :app:testDebugUnitTest --tests "*PassRepositoryUpdateTest*"`

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/data/PassDao.kt app/src/main/java/ch/bigli/passes/data/PassRepository.kt app/src/test/java/ch/bigli/passes/data/PassRepositoryUpdateTest.kt
git commit -m "feat: add updateTitle to rename a pass (blank-safe)"
```
Append the Co-Authored-By trailer.

---

## Task 2: `PassDetailViewModel.updateTitle`

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassDetailViewModel.kt`
- Test: `app/src/test/java/ch/bigli/passes/ui/PassDetailViewModelTest.kt`

- [ ] **Step 1: Write the failing test** — `app/src/test/java/ch/bigli/passes/ui/PassDetailViewModelTest.kt`
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

    @Test fun `updateTitle updates the on-screen pass`() = runTest {
        val imported = repo.import(fixture("sample.pkpass"), "sample.pkpass")
        val vm = PassDetailViewModel(repo, imported.id)
        vm.pass.first { it != null }
        vm.updateTitle("Renamed")
        assertEquals("Renamed", vm.pass.first { it?.title == "Renamed" }?.title)
    }

    @Test fun `updateTitle ignores blank input`() = runTest {
        val imported = repo.import(fixture("sample.pkpass"), "sample.pkpass")
        val vm = PassDetailViewModel(repo, imported.id)
        val loaded = vm.pass.first { it != null }!!
        vm.updateTitle("   ")
        assertEquals(loaded.title, vm.pass.first { it != null }?.title)
    }
}
```

- [ ] **Step 2: Run → verify FAIL** (`updateTitle` unresolved on the ViewModel).

Run: `./gradlew :app:testDebugUnitTest --tests "*PassDetailViewModelTest*"`

- [ ] **Step 3: Implement** — in `app/src/main/java/ch/bigli/passes/ui/PassDetailViewModel.kt`, add this method inside the class (after `delete`):
```kotlin
    /** Renames the pass and reflects the change on screen. Blank input is ignored. */
    fun updateTitle(newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repo.updateTitle(passId, trimmed)
            _pass.value = _pass.value?.copy(title = trimmed)
        }
    }
```
(`viewModelScope`, `launch`, `_pass`, `passId`, `repo` are already in scope.)

- [ ] **Step 4: Run → verify PASS** (2 tests).

Run: `./gradlew :app:testDebugUnitTest --tests "*PassDetailViewModelTest*"`

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/ui/PassDetailViewModel.kt app/src/test/java/ch/bigli/passes/ui/PassDetailViewModelTest.kt
git commit -m "feat: add PassDetailViewModel.updateTitle"
```
Append the Co-Authored-By trailer.

---

## Task 3: Detail-screen edit pencil + rename dialog

No unit test (Compose UI; verified on-device in Task 5).

**Files:**
- Modify (full replace): `app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt`

- [ ] **Step 1: Replace the ENTIRE contents** of `app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt` with:
```kotlin
package ch.bigli.passes.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import ch.bigli.passes.images.PassImage
import ch.bigli.passes.images.PassImageLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassDetailScreen(
    viewModel: PassDetailViewModel,
    imageLoader: PassImageLoader,
    onBack: () -> Unit,
    openTitleEditor: Boolean = false,
) {
    val pass by viewModel.pass.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showEditDialog by rememberSaveable { mutableStateOf(openTitleEditor) }

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
    val logo by produceState<Bitmap?>(initialValue = null, rawPath) {
        value = rawPath?.let { imageLoader.load(it, PassImage.LOGO) }
    }
    val strip by produceState<Bitmap?>(initialValue = null, rawPath) {
        value = rawPath?.let { imageLoader.load(it, PassImage.STRIP) }
    }

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
                actions = {
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit title", tint = fg)
                    }
                    IconButton(onClick = { viewModel.delete(onBack) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = fg)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = fg),
            )
        },
    ) { padding ->
        if (p == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding)) {
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
                Text(p.title, color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    p.fields.filter { it.position != FieldPosition.PRIMARY }.take(4).forEach { f ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
                            Text(f.value, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                p.barcode?.let { bc ->
                    val renderer = remember { BarcodeRenderer() }
                    val square = bc.format == BarcodeFormat.QR || bc.format == BarcodeFormat.AZTEC
                    val bmp = remember(bc) {
                        if (square) renderer.render(bc, 600, 600) else renderer.render(bc, 800, 300)
                    }
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(bmp.asImageBitmap(), contentDescription = "Barcode", modifier = Modifier.size(240.dp))
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
                Spacer(Modifier.weight(1f))
            }
        }
    }

    if (showEditDialog && p != null) {
        TitleEditDialog(
            initialTitle = p.title,
            onSave = { newTitle ->
                viewModel.updateTitle(newTitle)
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false },
        )
    }
}

@Composable
private fun TitleEditDialog(
    initialTitle: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit title") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Title") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
```

- [ ] **Step 2: Build + full test suite.**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
Run: `./gradlew :app:testDebugUnitTest` → all pass.

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt
git commit -m "feat: edit-title pencil and rename dialog on the detail screen"
```
Append the Co-Authored-By trailer.

---

## Task 4: `PendingPass` signal + auto-open on PDF import

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/PassApp.kt`
- Modify (full replace): `app/src/main/java/ch/bigli/passes/MainActivity.kt`

- [ ] **Step 1: Update `PassApp.kt`.** Replace the `pendingPassId` property and add a `PendingPass` type. In `app/src/main/java/ch/bigli/passes/PassApp.kt`:

Change:
```kotlin
    /** Set to a pass id when an import should navigate to that pass's detail screen; NavHost observes and clears it. */
    val pendingPassId = MutableStateFlow<String?>(null)
```
to:
```kotlin
    /** Set after an import so the NavHost navigates to the new pass; carries whether to open the title editor. */
    val pendingPass = MutableStateFlow<PendingPass?>(null)
```
And add this top-level data class at the end of the file (outside the `PassApp` class):
```kotlin
/** A just-imported pass to navigate to. [editTitle] auto-opens the rename dialog (used for PDF imports). */
data class PendingPass(val id: String, val editTitle: Boolean)
```

- [ ] **Step 2: Replace the ENTIRE contents** of `app/src/main/java/ch/bigli/passes/MainActivity.kt` with:
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
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.SourceFormat
import ch.bigli.passes.images.PassImageLoader
import ch.bigli.passes.importing.walletPassesTargetUrl
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
                    app.pendingPass.value = app.repository.importFromUrl(target).toPending()
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
                app.pendingPass.value = app.repository.importFromUri(uri).toPending()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** PDF-sourced passes open the title editor on arrival; others don't. */
private fun Pass.toPending() = PendingPass(id, editTitle = sourceFormat == SourceFormat.PDF)

private class VmFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

@Composable
private fun AppNav(app: PassApp) {
    val repo: PassRepository = app.repository
    val imageLoader = app.imageLoader
    val nav = rememberNavController()

    val pending by app.pendingPass.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { p ->
            nav.navigate("detail/${p.id}?editTitle=${p.editTitle}")
            app.pendingPass.value = null
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
                            app.pendingPass.value = repo.importFromUri(uri).toPending()
                        } catch (e: Exception) {
                            vm.reportError(e.message ?: "Import failed")
                        }
                    }
                }
            }
            PassListScreen(
                viewModel = vm,
                imageLoader = imageLoader,
                onImportClick = { picker.launch(arrayOf("*/*")) },
                onPassClick = { id -> nav.navigate("detail/$id") },
            )
        }
        composable(
            "detail/{id}?editTitle={editTitle}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("editTitle") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val id = entry.arguments!!.getString("id")!!
            val editTitle = entry.arguments!!.getBoolean("editTitle")
            val vm: PassDetailViewModel = viewModel(factory = VmFactory { PassDetailViewModel(repo, id) })
            PassDetailScreen(
                viewModel = vm,
                imageLoader = imageLoader,
                onBack = { nav.popBackStack() },
                openTitleEditor = editTitle,
            )
        }
    }
}
```
NOTE: `onPassClick` still navigates to `"detail/$id"` (no `editTitle`); the optional query arg defaults to `false`, so tapping a pass in the list never auto-opens the editor. Only the post-import navigation passes `editTitle=true` (for PDFs).

- [ ] **Step 3: Build + full test suite.**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (fix any real compile error).
Run: `./gradlew :app:testDebugUnitTest` → all pass (no regression).

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/PassApp.kt app/src/main/java/ch/bigli/passes/MainActivity.kt
git commit -m "feat: auto-open the rename dialog after a PDF import"
```
Append the Co-Authored-By trailer.

---

## Task 5: On-device verification

- [ ] **Step 1: Install** the debug build: `./gradlew :app:installDebug`.
- [ ] **Step 2: Hand off to the user.** Ask the user to confirm:
  - **Manual rename:** open any pass → tap the **pencil** in the top bar → the dialog shows the current title → change it, **Save** → the title updates on the detail screen and in the list; **Cancel** leaves it unchanged; an empty field disables **Save**.
  - **PDF auto-prompt:** import a **PDF** (via `+`) → after it imports, the rename dialog **auto-opens pre-filled** with the file-name title → editing + Save renames it; Cancel keeps the file-name title.
  - **No prompt for pkpass:** importing a `.pkpass` (via `+` or a walletpasses link) does **not** auto-open the dialog.
  Do NOT capture screenshots; rely on the user's confirmation.
- [ ] **Step 3: Commit** any fixes made during verification (if none, skip).

---

## Self-Review notes

- **Spec coverage:** DAO+repo `updateTitle` blank-safe (Task 1); ViewModel `updateTitle` reflecting in `_pass` (Task 2); edit pencil + `AlertDialog` with pre-filled text, Save-disabled-when-blank, Cancel (Task 3); `PendingPass(id, editTitle)` signal + `editTitle` nav arg + auto-open for PDF only (Task 4); device verification of manual rename, PDF auto-prompt, and pkpass-no-prompt (Task 5).
- **Type consistency:** `updateTitle(id, title)` (DAO/repo) and `updateTitle(newTitle)` (VM); `PendingPass(id, editTitle)`; `pendingPass: MutableStateFlow<PendingPass?>`; `PassDetailScreen(..., openTitleEditor: Boolean = false)`; the `detail/{id}?editTitle={editTitle}` route with a `BoolType` default-false arg. All call sites updated in `MainActivity`.
- **Deferred (unchanged):** editing fields other than the title; renaming from the list screen (done from detail only).
