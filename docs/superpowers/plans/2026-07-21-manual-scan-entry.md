# Manual / Scan Barcode Entry — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a pass by scanning a barcode with the camera (CameraX + ZXing) or typing it manually; both create a `MANUAL` generic pass.

**Architecture:** The `+` FAB opens a bottom-sheet (Import / Scan / Manual). Scanning uses a CameraX `ImageAnalysis` analyzer decoding the Y-plane with ZXing; the result and manual entry share one create form. `PassRepository.createManualPass` builds a `MANUAL` pass with `rawFilePath=""`.

**Tech Stack:** Kotlin, CameraX 1.6.1, ZXing, Jetpack Compose (Material 3), Room, Robolectric.

**Environment:** Run `./gradlew <task>` BARE (daemon toolchain provides JDK 21). Branch: `feat/manual-scan-entry` (already checked out). Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. **Device verification: build + install, then hand to the user — no `adb screencap`/`adb input`.**

---

## Task 1: `PassRepository.createManualPass`

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassRepositoryManualTest.kt`

- [ ] **Step 1: Failing test** — `app/src/test/java/ch/bigli/passes/data/PassRepositoryManualTest.kt`
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
        val pass = repo.createManualPass("Coop card", BarcodeFormat.CODE128, "6001234567890")
        val stored = repo.getById(pass.id)!!
        assertEquals("Coop card", stored.title)
        assertEquals(PassType.GENERIC, stored.type)
        assertEquals(SourceFormat.MANUAL, stored.sourceFormat)
        assertEquals(BarcodeFormat.CODE128, stored.barcode!!.format)
        assertEquals("6001234567890", stored.barcode!!.message)
        assertEquals("", stored.rawFilePath)
        assertEquals(1, repo.observeAll().first().size)
    }
}
```

- [ ] **Step 2: Run → FAIL.** `./gradlew :app:testDebugUnitTest --tests "*PassRepositoryManualTest*"`

- [ ] **Step 3: Implement.** In `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`, add these imports (with the existing ones):
```kotlin
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.PassType
```
(`ch.bigli.passes.domain.SourceFormat`, `Pass`, and `java.util.UUID` are already imported.)
Add this method inside the class (e.g. after `updateTitle`):
```kotlin
    /** Creates a pass from a manually-entered or scanned barcode (no source file). */
    suspend fun createManualPass(title: String, format: BarcodeFormat, value: String): Pass =
        withContext(Dispatchers.IO) {
            val pass = Pass(
                id = UUID.randomUUID().toString(),
                type = PassType.GENERIC,
                title = title.trim(),
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
```

- [ ] **Step 4: Run → PASS.** `./gradlew :app:testDebugUnitTest --tests "*PassRepositoryManualTest*"`

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/data/PassRepository.kt app/src/test/java/ch/bigli/passes/data/PassRepositoryManualTest.kt
git commit -m "feat: add createManualPass for scanned/typed barcodes"
```
Append the trailer.

---

## Task 2: Scanning core — CameraX deps, permission, ZXing analyzer

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/ch/bigli/passes/barcode/ZxingSupport.kt`
- Modify: `app/src/main/java/ch/bigli/passes/barcode/BarcodeScanner.kt`
- Create: `app/src/main/java/ch/bigli/passes/barcode/CameraBarcodeAnalyzer.kt`

- [ ] **Step 1: Add CameraX to the version catalog.** In `gradle/libs.versions.toml`, add under `[versions]`:
```toml
camerax = "1.6.1"
```
and under `[libraries]`:
```toml
androidx-camera-core = { module = "androidx.camera:camera-core", version.ref = "camerax" }
androidx-camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
androidx-camera-view = { module = "androidx.camera:camera-view", version.ref = "camerax" }
```

- [ ] **Step 2: Add the dependencies.** In `app/build.gradle.kts`, add inside `dependencies { }` (with the other `implementation` lines):
```kotlin
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
```

- [ ] **Step 3: Manifest permission.** In `app/src/main/AndroidManifest.xml`, add these as children of `<manifest>` (next to the existing `<uses-permission android:name="android.permission.INTERNET" />`):
```xml
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.any" android:required="false" />
```

- [ ] **Step 4: Shared ZXing helpers** — create `app/src/main/java/ch/bigli/passes/barcode/ZxingSupport.kt`
```kotlin
package ch.bigli.passes.barcode

import ch.bigli.passes.domain.BarcodeFormat
import com.google.zxing.BarcodeFormat as ZxFormat
import com.google.zxing.DecodeHintType

/** ZXing decode hints shared by the still-image and camera scanners. */
internal fun zxingHints(): Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(
        ZxFormat.QR_CODE, ZxFormat.PDF_417, ZxFormat.AZTEC, ZxFormat.CODE_128,
    ),
    DecodeHintType.TRY_HARDER to true,
)

/** Maps a ZXing format to our domain format, or null if unsupported. */
internal fun ZxFormat.toDomain(): BarcodeFormat? = when (this) {
    ZxFormat.QR_CODE -> BarcodeFormat.QR
    ZxFormat.PDF_417 -> BarcodeFormat.PDF417
    ZxFormat.AZTEC -> BarcodeFormat.AZTEC
    ZxFormat.CODE_128 -> BarcodeFormat.CODE128
    else -> null
}
```

- [ ] **Step 5: Refactor `BarcodeScanner` to use the shared helpers** — replace the contents of `app/src/main/java/ch/bigli/passes/barcode/BarcodeScanner.kt` with:
```kotlin
package ch.bigli.passes.barcode

import android.graphics.Bitmap
import ch.bigli.passes.domain.Barcode
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Detects a supported barcode inside a bitmap (e.g. a rendered PDF page) using ZXing. */
class BarcodeScanner {
    fun scan(bitmap: Bitmap): Barcode? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val result = try {
            MultiFormatReader().decode(binary, zxingHints())
        } catch (e: Exception) {
            return null
        }
        val format = result.barcodeFormat.toDomain() ?: return null
        return Barcode(format, result.text, null)
    }
}
```

- [ ] **Step 6: Camera analyzer** — create `app/src/main/java/ch/bigli/passes/barcode/CameraBarcodeAnalyzer.kt`
```kotlin
package ch.bigli.passes.barcode

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import ch.bigli.passes.domain.Barcode
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX analyzer that decodes the first barcode it sees from the frame's luminance (Y) plane,
 * then calls [onResult] exactly once. Set as the analyzer with a main-thread executor so [onResult]
 * is delivered on the main thread.
 */
class CameraBarcodeAnalyzer(private val onResult: (Barcode) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply { setHints(zxingHints()) }
    private val done = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (done.get()) { image.close(); return }
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val source = PlanarYUVLuminanceSource(
                data, plane.rowStride, image.height,
                0, 0, image.width, image.height, false,
            )
            val result = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            } catch (e: Exception) {
                null
            } finally {
                reader.reset()
            }
            val format = result?.barcodeFormat?.toDomain()
            if (result != null && format != null && done.compareAndSet(false, true)) {
                onResult(Barcode(format, result.text, null))
            }
        } finally {
            image.close()
        }
    }
}
```

- [ ] **Step 7: Build + tests.**
`./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (CameraX resolves).
`./gradlew :app:testDebugUnitTest` → all pass — in particular `BarcodeScannerTest` still green after the refactor.

- [ ] **Step 8: Commit**
```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/ch/bigli/passes/barcode
git commit -m "feat: add CameraX scanning core (shared ZXing helpers + analyzer)"
```
Append the trailer.

---

## Task 3: `ScanScreen`

No unit test (camera/permission). Verified on device.

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/ui/ScanScreen.kt`

- [ ] **Step 1: Implement** — `app/src/main/java/ch/bigli/passes/ui/ScanScreen.kt`
```kotlin
package ch.bigli.passes.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.bigli.passes.barcode.CameraBarcodeAnalyzer
import ch.bigli.passes.domain.Barcode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onScanned: (Barcode) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan barcode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (hasPermission) {
                val analyzer = remember { CameraBarcodeAnalyzer(onResult = onScanned) }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { it.setAnalyzer(ContextCompat.getMainExecutor(ctx), analyzer) }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                            )
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                )
            } else {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Camera permission is needed to scan a barcode.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant permission")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Build.** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. (If `LocalLifecycleOwner` import is unresolved, it comes from `androidx.lifecycle.compose` — the `androidx.lifecycle:lifecycle-runtime-compose` artifact is pulled transitively by `lifecycle-viewmodel-compose`; if not, add `implementation("androidx.lifecycle:lifecycle-runtime-compose")` via a catalog entry and report it.)

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/ui/ScanScreen.kt
git commit -m "feat: add camera ScanScreen (CameraX preview + permission)"
```
Append the trailer.

---

## Task 4: `CreatePassScreen` + `pendingScan`

No unit test (form UI). Verified on device.

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/PassApp.kt`
- Create: `app/src/main/java/ch/bigli/passes/ui/CreatePassScreen.kt`

- [ ] **Step 1: Add the scan holder to `PassApp`.** In `app/src/main/java/ch/bigli/passes/PassApp.kt`, add inside the class (after `pendingPass`):
```kotlin
    /** A scanned barcode handed from ScanScreen to CreatePassScreen as a prefill; consumed once. */
    val pendingScan = MutableStateFlow<ch.bigli.passes.domain.Barcode?>(null)
```

- [ ] **Step 2: Implement the form** — `app/src/main/java/ch/bigli/passes/ui/CreatePassScreen.kt`
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
    onCreate: (title: String, format: BarcodeFormat, value: String) -> Unit,
    onBack: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
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
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
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
                onClick = { onCreate(title, format, value) },
                enabled = title.isNotBlank() && value.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create pass") }
        }
    }
}
```
NOTE: `ExposedDropdownMenu` is a member of `ExposedDropdownMenuBox`'s scope — call it directly inside the box as shown (do not add a separate import for it). `BarcodeFormat.entries` requires Kotlin enum `entries` (available on Kotlin 2.x — fine). If `MenuAnchorType`/`menuAnchor(MenuAnchorType…)` is unresolved on this Material3 version, fall back to the no-arg `Modifier.menuAnchor()` and report it.

- [ ] **Step 3: Build.** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. `./gradlew :app:testDebugUnitTest` → still green.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/PassApp.kt app/src/main/java/ch/bigli/passes/ui/CreatePassScreen.kt
git commit -m "feat: add CreatePassScreen form and pendingScan holder"
```
Append the trailer.

---

## Task 5: `+` menu + scan/create navigation

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt`
- Modify: `app/src/main/java/ch/bigli/passes/MainActivity.kt`

- [ ] **Step 1: Bottom-sheet menu in `PassListScreen`.** In `app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt`:

Add imports (with the existing ones):
```kotlin
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.navigationBarsPadding
```
(`androidx.compose.material.icons.filled.Add`, `Column`, `Text`, `Icon`, `getValue`, `remember` are already imported.)

Change the `PassListScreen` signature to add the two new callbacks (keep the others):
```kotlin
@Composable
fun PassListScreen(
    viewModel: PassListViewModel,
    imageLoader: PassImageLoader,
    onImportClick: () -> Unit,
    onScanClick: () -> Unit,
    onManualClick: () -> Unit,
    onPassClick: (String) -> Unit,
) {
```

Inside the function body, add sheet state right after the existing `val snackbar = remember { SnackbarHostState() }` line:
```kotlin
    var showAddSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
```

Change the FAB `onClick` from `onImportClick` to open the sheet:
```kotlin
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add pass")
            }
```

Add the bottom sheet at the very end of the `PassListScreen` function body, AFTER the `Scaffold(...) { ... }` block closes (as a sibling composable):
```kotlin
    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                ListItem(
                    headlineContent = { Text("Import file") },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    modifier = Modifier.clickable { showAddSheet = false; onImportClick() },
                )
                ListItem(
                    headlineContent = { Text("Scan barcode") },
                    leadingContent = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                    modifier = Modifier.clickable { showAddSheet = false; onScanClick() },
                )
                ListItem(
                    headlineContent = { Text("Enter manually") },
                    leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    modifier = Modifier.clickable { showAddSheet = false; onManualClick() },
                )
            }
        }
    }
```
(`clickable` and `Column` are already imported in this file.)

- [ ] **Step 2: Wire routes in `MainActivity`.** In `app/src/main/java/ch/bigli/passes/MainActivity.kt`:

Add imports (with the existing ones):
```kotlin
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.ui.CreatePassScreen
import ch.bigli.passes.ui.ScanScreen
```

Update the `PassListScreen(...)` call inside `composable("list")` to pass the two new callbacks:
```kotlin
            PassListScreen(
                viewModel = vm,
                imageLoader = imageLoader,
                onImportClick = { picker.launch(arrayOf("*/*")) },
                onScanClick = { nav.navigate("scan") },
                onManualClick = { app.pendingScan.value = null; nav.navigate("create") },
                onPassClick = { id -> nav.navigate("detail/$id") },
            )
```

Add two new `composable(...)` destinations inside the `NavHost { ... }` (e.g. after the `composable("list") { ... }` block):
```kotlin
        composable("scan") {
            ScanScreen(
                onScanned = { barcode ->
                    app.pendingScan.value = barcode
                    nav.navigate("create") { popUpTo("scan") { inclusive = true } }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("create") {
            val scope = rememberCoroutineScope()
            val prefill: Barcode? = remember { app.pendingScan.value }
            CreatePassScreen(
                prefill = prefill,
                onCreate = { title, format, value ->
                    scope.launch {
                        val pass = repo.createManualPass(title, format, value)
                        app.pendingScan.value = null
                        nav.navigate("detail/${pass.id}") { popUpTo("list") }
                    }
                },
                onBack = { app.pendingScan.value = null; nav.popBackStack() },
            )
        }
```
(`rememberCoroutineScope`, `remember`, `launch` are already imported in `MainActivity`.)

- [ ] **Step 3: Build + tests.**
`./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
`./gradlew :app:testDebugUnitTest` → all pass.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt app/src/main/java/ch/bigli/passes/MainActivity.kt
git commit -m "feat: add pass menu with scan and manual entry"
```
Append the trailer.

---

## Task 6: On-device verification

- [ ] **Step 1: Install.** `./gradlew :app:installDebug`.
- [ ] **Step 2: Hand off to the user.** Ask them to confirm:
  - **Menu:** tapping `+` shows a sheet with **Import file / Scan barcode / Enter manually**.
  - **Manual:** Enter manually → fill Title + Barcode value, pick a Format, Create → a new pass appears and opens, showing the barcode; Create is disabled until Title and value are filled.
  - **Scan:** Scan barcode → grants camera permission → point at any barcode (e.g. one of the app's own passes on another screen, or a product barcode) → it jumps to the create form pre-filled with the value/format → Create → the pass is added.
  - **Permission denied:** deny camera → an explanation + "Grant permission" button shows, no crash; Back works.
  - **Import file** still works as before.
  Do NOT capture screenshots.
- [ ] **Step 3: Commit** any fixes (if none, skip).

---

## Self-Review notes

- **Spec coverage:** `createManualPass` MANUAL/GENERIC/`rawFilePath=""` (Task 1); CameraX deps + CAMERA permission + shared ZXing helpers + Y-plane analyzer (Task 2); CameraX preview + runtime permission (Task 3); shared create form + `pendingScan` prefill (Task 4); `+` bottom-sheet + scan/create routes (Task 5); device verification incl. permission-denied (Task 6).
- **Refactor:** `BarcodeScanner` now shares `zxingHints()`/`toDomain()` with the analyzer; its existing round-trip test guards the refactor.
- **Type consistency:** `createManualPass(title, format, value): Pass`; `CameraBarcodeAnalyzer(onResult: (Barcode) -> Unit)`; `ScanScreen(onScanned: (Barcode) -> Unit, onBack)`; `CreatePassScreen(prefill: Barcode?, onCreate: (String, BarcodeFormat, String) -> Unit, onBack)`; `PassApp.pendingScan: MutableStateFlow<Barcode?>`; `PassListScreen(..., onScanClick, onManualClick, ...)`. All call sites updated in `MainActivity`.
- **Deferred:** Google Wallet JSON; editing a pass's barcode after creation; multi-scan.
