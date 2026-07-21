# Pass Strip & Logo Rendering — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the `logo` and `strip` artwork embedded in `.pkpass` files so passes look recognizable (motivating case: a Loopy Loyalty stamp card whose identity is its `strip@2x.png`).

**Architecture:** A new `PassImageLoader` extracts `logo`/`strip` on-demand from the already-stored raw `.pkpass` (a zip), decoding on a background thread and caching in memory. It's app-scoped (one instance in `PassApp`), threaded through `MainActivity`'s `AppNav` into the Compose screens. No change to the `Pass` model or Room schema — everything derives from `pass.rawFilePath`. This works retroactively for already-imported passes.

**Tech Stack:** Kotlin, Jetpack Compose, `java.util.zip.ZipFile`, `android.graphics.BitmapFactory`, `android.util.LruCache`, Robolectric for JVM tests.

**Environment:** Run `./gradlew <task>` bare (the daemon JVM toolchain provides JDK 21 — do NOT prefix `JAVA_HOME`). Toolchain: AGP 9.3.0, Gradle 9.5.0, Kotlin 2.2.10, KSP 2.2.10-2.0.2, Room 2.8.4. Branch: `feat/pass-strip-logo` (already checked out). Commit messages end with a real-newline trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. **On-device verification: build + install, then hand off to the user to confirm — do NOT run `adb screencap`/`adb input`.**

---

## File Structure

```
app/src/main/java/ch/bigli/passes/
    images/PassImageLoader.kt        NEW  enum PassImage, bestImageEntry(), PassImageLoader
    PassApp.kt                       MODIFY  + val imageLoader
    MainActivity.kt                  MODIFY  thread imageLoader into screens
    ui/PassDetailScreen.kt           MODIFY  logo in top bar + full-width strip
    ui/PassListScreen.kt             MODIFY  small logo in card header
app/src/test/java/ch/bigli/passes/
    images/BestImageEntryTest.kt     NEW  pure resolution-selection tests (no Robolectric)
    images/PassImageLoaderTest.kt    NEW  Robolectric load()/cache tests
app/src/test/resources/fixtures/
    withimages.pkpass                NEW  fixture with tiny PNGs
```

---

## Task 1: `PassImageLoader` + fixture + tests

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/images/PassImageLoader.kt`
- Create: `app/src/test/java/ch/bigli/passes/images/BestImageEntryTest.kt`
- Create: `app/src/test/java/ch/bigli/passes/images/PassImageLoaderTest.kt`
- Create fixture: `app/src/test/resources/fixtures/withimages.pkpass`

- [ ] **Step 1: Build the image fixture.** Run this (generates tiny valid PNGs at distinct sizes with pure-stdlib Python, then zips them with a minimal storeCard `pass.json`):

```bash
mkdir -p app/src/test/resources/fixtures
python3 - <<'PY'
import struct, zlib, os, tempfile
def png(path, w, h):
    def chunk(typ, data):
        return struct.pack(">I", len(data)) + typ + data + struct.pack(">I", zlib.crc32(typ+data) & 0xffffffff)
    raw = b"".join(b"\x00" + b"\xff\x00\x00"*w for _ in range(h))  # filter 0 + red RGB rows
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)            # 8-bit truecolor
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))

d = tempfile.mkdtemp()
open(os.path.join(d, "pass.json"), "w").write(
    '{"formatVersion":1,"passTypeIdentifier":"pass.test","serialNumber":"IMG1",'
    '"teamIdentifier":"T","organizationName":"ImgOrg","description":"has images",'
    '"barcode":{"format":"PKBarcodeFormatQR","message":"IMG","messageEncoding":"iso-8859-1"},'
    '"storeCard":{"secondaryFields":[{"key":"s","label":"L","value":"v"}]}}')
png(os.path.join(d, "logo@2x.png"), 2, 2)
png(os.path.join(d, "logo@3x.png"), 3, 3)
png(os.path.join(d, "strip@2x.png"), 4, 2)

import zipfile
out = "app/src/test/resources/fixtures/withimages.pkpass"
with zipfile.ZipFile(out, "w") as z:
    for name in ("pass.json", "logo@2x.png", "logo@3x.png", "strip@2x.png"):
        z.write(os.path.join(d, name), name)
print("wrote", out)
PY
unzip -l app/src/test/resources/fixtures/withimages.pkpass
```
Expected: the archive lists `pass.json`, `logo@2x.png`, `logo@3x.png`, `strip@2x.png`.

- [ ] **Step 2: Write the failing pure test** — `app/src/test/java/ch/bigli/passes/images/BestImageEntryTest.kt`
```kotlin
package ch.bigli.passes.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BestImageEntryTest {
    private val entries = setOf("pass.json", "logo@2x.png", "logo@3x.png", "strip@2x.png")

    @Test fun `prefers @3x over @2x`() {
        assertEquals("logo@3x.png", bestImageEntry(entries, "logo"))
    }

    @Test fun `falls back to @2x when @3x absent`() {
        assertEquals("strip@2x.png", bestImageEntry(entries, "strip"))
    }

    @Test fun `falls back to base name when only base present`() {
        assertEquals("logo.png", bestImageEntry(setOf("logo.png"), "logo"))
    }

    @Test fun `returns null when image absent`() {
        assertNull(bestImageEntry(entries, "thumbnail"))
    }
}
```

- [ ] **Step 3: Run → verify FAIL** (`bestImageEntry` unresolved).

Run: `./gradlew :app:testDebugUnitTest --tests "*BestImageEntryTest*"`
Expected: compile failure, `Unresolved reference 'bestImageEntry'`.

- [ ] **Step 4: Write the failing loader test** — `app/src/test/java/ch/bigli/passes/images/PassImageLoaderTest.kt`
```kotlin
package ch.bigli.passes.images

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PassImageLoaderTest {
    private val loader = PassImageLoader()

    /** Copies a classpath fixture to a real temp file, since PassImageLoader opens a file path. */
    private fun fixtureFile(name: String): String {
        val bytes = checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()
        val f = File.createTempFile(name, ".pkpass")
        f.writeBytes(bytes)
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test fun `loads logo and strip from a pkpass with images`() = runTest {
        val path = fixtureFile("withimages.pkpass")
        assertNotNull(loader.load(path, PassImage.LOGO))
        assertNotNull(loader.load(path, PassImage.STRIP))
    }

    @Test fun `returns null for a pkpass without images`() = runTest {
        val path = fixtureFile("sample.pkpass") // Phase 1 fixture: pass.json only, no images
        assertNull(loader.load(path, PassImage.LOGO))
    }

    @Test fun `returns null for an unreadable path`() = runTest {
        assertNull(loader.load("/definitely/not/here.pkpass", PassImage.LOGO))
    }

    @Test fun `caches the decoded bitmap`() = runTest {
        val path = fixtureFile("withimages.pkpass")
        val first = loader.load(path, PassImage.LOGO)
        val second = loader.load(path, PassImage.LOGO)
        assertNotNull(first)
        assertSame(first, second)
    }
}
```

- [ ] **Step 5: Run → verify FAIL** (`PassImageLoader`/`PassImage` unresolved).

Run: `./gradlew :app:testDebugUnitTest --tests "*PassImageLoaderTest*"`
Expected: compile failure.

- [ ] **Step 6: Implement** — `app/src/main/java/ch/bigli/passes/images/PassImageLoader.kt`
```kotlin
package ch.bigli.passes.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile

enum class PassImage(val baseName: String) { LOGO("logo"), STRIP("strip") }

/** Best available resolution for [baseName] among zip [names], preferring @3x then @2x then base. */
internal fun bestImageEntry(names: Set<String>, baseName: String): String? =
    listOf("$baseName@3x.png", "$baseName@2x.png", "$baseName.png").firstOrNull { it in names }

/**
 * Loads pkpass [PassImage]s on-demand from the stored raw `.pkpass` zip at a given file path,
 * decoded off the main thread and cached in memory. Returns null if the image is absent or the
 * file/zip is unreadable; never throws to the caller.
 */
class PassImageLoader {
    private val cache = LruCache<String, Bitmap>(16)

    suspend fun load(rawFilePath: String, image: PassImage): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$rawFilePath#${image.baseName}"
        cache.get(key)?.let { return@withContext it }
        val bitmap = runCatching {
            ZipFile(rawFilePath).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                val entryName = bestImageEntry(names, image.baseName) ?: return@use null
                zip.getInputStream(zip.getEntry(entryName)).use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
        if (bitmap != null) cache.put(key, bitmap)
        bitmap
    }
}
```

- [ ] **Step 7: Run → verify PASS** (both test classes).

Run: `./gradlew :app:testDebugUnitTest --tests "*BestImageEntryTest*" --tests "*PassImageLoaderTest*"`
Expected: all 8 tests pass.

- [ ] **Step 8: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/images app/src/test/java/ch/bigli/passes/images app/src/test/resources/fixtures/withimages.pkpass
git commit -m "feat: add PassImageLoader for on-demand pkpass logo/strip extraction"
```
Append the Co-Authored-By trailer.

---

## Task 2: App wiring + strip/logo on the detail screen

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/PassApp.kt`
- Modify: `app/src/main/java/ch/bigli/passes/MainActivity.kt`
- Modify (full replace): `app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt`

- [ ] **Step 1: Add the loader to `PassApp`.** In `PassApp.kt`, add an import and a property.

Add import (with the existing imports):
```kotlin
import ch.bigli.passes.images.PassImageLoader
```
Add inside the `PassApp` class (after `pendingPassId`):
```kotlin
    /** Shared, process-wide image loader (in-memory cache) for pkpass logo/strip artwork. */
    val imageLoader = PassImageLoader()
```

- [ ] **Step 2: Thread the loader through `AppNav`.** In `MainActivity.kt`, inside `AppNav`, read the loader next to `repo` and pass it to both screens.

Change this line (currently near the top of `AppNav`):
```kotlin
    val repo: PassRepository = app.repository
```
to:
```kotlin
    val repo: PassRepository = app.repository
    val imageLoader = app.imageLoader
```
Change the `PassListScreen(` call so it includes the loader (leave the other args as-is):
```kotlin
            PassListScreen(
                viewModel = vm,
                imageLoader = imageLoader,
                onImportClick = { picker.launch(arrayOf("*/*")) },
                onPassClick = { id -> nav.navigate("detail/$id") },
            )
```
Change the `PassDetailScreen(` call to:
```kotlin
            PassDetailScreen(viewModel = vm, imageLoader = imageLoader, onBack = { nav.popBackStack() })
```
Add this import to `MainActivity.kt` (with the other `ch.bigli.passes.ui` imports):
```kotlin
import ch.bigli.passes.images.PassImageLoader
```

- [ ] **Step 3: Render logo + strip in the detail screen.** Replace the ENTIRE contents of `app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt` with:
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
) {
    val pass by viewModel.pass.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
}
```

- [ ] **Step 4: Build + run the full test suite.**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (fix any real compile errors).
Run: `./gradlew :app:testDebugUnitTest` → all pass (no regression).

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/PassApp.kt app/src/main/java/ch/bigli/passes/MainActivity.kt app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt
git commit -m "feat: render pkpass logo and strip on the detail screen"
```
Append the Co-Authored-By trailer.

---

## Task 3: Logo on the list card

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt`

- [ ] **Step 1: Add the loader param + logo rendering.** In `PassListScreen.kt`:

Add these imports (with the existing ones):
```kotlin
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import ch.bigli.passes.images.PassImage
import ch.bigli.passes.images.PassImageLoader
```
(Some of these — `Image`, `getValue` — may already be imported; do not duplicate an existing import.)

Change the `PassListScreen` signature to accept the loader:
```kotlin
@Composable
fun PassListScreen(
    viewModel: PassListViewModel,
    imageLoader: PassImageLoader,
    onImportClick: () -> Unit,
    onPassClick: (String) -> Unit,
) {
```
Change the `PassCard` call site (inside the `items(...)` block) to pass the loader:
```kotlin
                items(passes, key = { it.id }) { pass ->
                    PassCard(pass, imageLoader) { onPassClick(pass.id) }
                }
```
Replace the `PassCard` composable with:
```kotlin
@Composable
private fun PassCard(pass: Pass, imageLoader: PassImageLoader, onClick: () -> Unit) {
    val bgColor = pass.bgColor
    val bg = bgColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val fg = if (bgColor != null) Color(legibleTextColor(bgColor, pass.fgColor))
             else pass.fgColor?.let { Color(it) } ?: Color.White
    val logo by produceState<Bitmap?>(initialValue = null, pass.rawFilePath) {
        value = imageLoader.load(pass.rawFilePath, PassImage.LOGO)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        logo?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.height(24.dp).padding(bottom = 8.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            (pass.subtitle ?: pass.organization ?: pass.type.name).uppercase(),
            color = fg.copy(alpha = 0.85f), fontSize = 11.sp,
        )
        Text(pass.title, color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        val summary = pass.fields.filter { it.position != FieldPosition.PRIMARY }
            .take(3).joinToString("   ") { "${it.label}: ${it.value}" }
        if (summary.isNotEmpty()) {
            Text(summary, color = fg.copy(alpha = 0.9f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
```

- [ ] **Step 2: Build + tests.**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
Run: `./gradlew :app:testDebugUnitTest` → all pass.

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt
git commit -m "feat: show pkpass logo on the list card"
```
Append the Co-Authored-By trailer.

---

## Task 4: On-device verification

- [ ] **Step 1: Install** the debug build: `./gradlew :app:installDebug`.
- [ ] **Step 2: Hand off to the user.** Ask the user to open the app and confirm:
  - The **list** shows the Stettbacher card with its **logo** at the top of the card.
  - Opening it shows the **strip** artwork (the stamp-card image) full-width under the header, the **logo** in the top bar (in place of the "stettbacher" text), and the barcode still renders below.
  - The SWISS boarding-pass card (if re-imported) is unaffected (it has no strip; logo only if present).
  Do NOT capture screenshots; rely on the user's confirmation.
- [ ] **Step 3: Commit** any fixes made during verification (if none, skip).

---

## Self-Review notes

- **Spec coverage:** on-demand extraction + cache (`PassImageLoader`, Task 1); @3x→@2x→base resolution (`bestImageEntry`, Task 1); app-scoped shared instance + wiring (Task 2); logo replaces org text with fallback + full-width strip on detail (Task 2); small logo on list card (Task 3); missing/corrupt/unreadable → null, no crash (Task 1 tests); no `Pass`/schema change (nothing touches the model). Contrast (`legibleTextColor`) untouched — strip/logo carry no overlaid text.
- **Deferred (unchanged):** thumbnail/background/footer images; other formats (Phase 3); auto-update/signature (Phase 4).
- **Type consistency:** `PassImage { LOGO, STRIP }`, `PassImageLoader.load(rawFilePath, image): Bitmap?`, and `bestImageEntry(names, baseName): String?` are defined once in Task 1 and used verbatim in Tasks 2–3; `PassDetailScreen`/`PassListScreen` gain an `imageLoader: PassImageLoader` param matched at every call site in `MainActivity`.
