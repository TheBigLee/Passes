# PDF Ticket Import — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import a PDF ticket by detecting its embedded barcode and storing it as a scannable generic pass (title from the file name).

**Architecture:** A testable `BarcodeScanner` (ZXing over a Bitmap) + a `PdfImporter` (Android `PdfRenderer` → bitmap → scanner → `Pass`). `PassRepository` routes by magic bytes via a pure `detectPassFormat` helper. The `PassImporter` interface gains a `displayName` param so PDF passes can be titled from the file name.

**Tech Stack:** Kotlin, ZXing (`com.google.zxing`), `android.graphics.pdf.PdfRenderer`, Robolectric.

**Environment:** Run `./gradlew <task>` BARE — no `JAVA_HOME` prefix (daemon toolchain provides JDK 21). Branch: `feat/pdf-import` (already checked out). Commit messages end with a real-newline trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. **Device verification: build + install, then hand off to the user — do NOT run `adb screencap`/`adb input`.**

---

## File Structure

```
app/src/main/java/ch/bigli/passes/
    barcode/BarcodeScanner.kt        NEW  ZXing detection from a Bitmap
    importing/PassImporter.kt        MODIFY  + displayName param
    importing/PkPassImporter.kt      MODIFY  accept + ignore displayName
    importing/PdfImporter.kt         NEW  PdfRenderer -> BarcodeScanner -> Pass
    data/PassFormatDetector.kt       NEW  pure magic-byte format detection
    data/PassRepository.kt           MODIFY  route via detector + inject PdfImporter
app/src/test/java/ch/bigli/passes/
    barcode/BarcodeScannerTest.kt    NEW  render->scan round-trip tests
    data/PassFormatDetectorTest.kt   NEW  pure routing tests
    importing/PkPassImporterTest.kt  MODIFY  update 3 call sites for displayName
```

---

## Task 1: `BarcodeScanner`

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/barcode/BarcodeScanner.kt`
- Test: `app/src/test/java/ch/bigli/passes/barcode/BarcodeScannerTest.kt`

- [ ] **Step 1: Write the failing test** — `app/src/test/java/ch/bigli/passes/barcode/BarcodeScannerTest.kt`
```kotlin
package ch.bigli.passes.barcode

import android.graphics.Bitmap
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BarcodeScannerTest {
    private val renderer = BarcodeRenderer()
    private val scanner = BarcodeScanner()

    @Test fun `round-trips a QR code`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.QR, "HELLO", null), 400, 400)
        val found = scanner.scan(bmp)
        assertNotNull(found)
        assertEquals(BarcodeFormat.QR, found!!.format)
        assertEquals("HELLO", found.message)
    }

    @Test fun `round-trips a code128`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.CODE128, "12345678", null), 600, 300)
        val found = scanner.scan(bmp)
        assertNotNull(found)
        assertEquals(BarcodeFormat.CODE128, found!!.format)
        assertEquals("12345678", found.message)
    }

    @Test fun `returns null for a blank image`() {
        val blank = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        assertNull(scanner.scan(blank))
    }
}
```

- [ ] **Step 2: Run → verify FAIL** (`BarcodeScanner` unresolved).

Run: `./gradlew :app:testDebugUnitTest --tests "*BarcodeScannerTest*"`
Expected: compile failure.

- [ ] **Step 3: Implement** — `app/src/main/java/ch/bigli/passes/barcode/BarcodeScanner.kt`
```kotlin
package ch.bigli.passes.barcode

import android.graphics.Bitmap
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import com.google.zxing.BarcodeFormat as ZxFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
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
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                ZxFormat.QR_CODE, ZxFormat.PDF_417, ZxFormat.AZTEC, ZxFormat.CODE_128,
            ),
            DecodeHintType.TRY_HARDER to true,
        )
        val result = try {
            MultiFormatReader().decode(binary, hints)
        } catch (e: Exception) {
            return null
        }
        val format = when (result.barcodeFormat) {
            ZxFormat.QR_CODE -> BarcodeFormat.QR
            ZxFormat.PDF_417 -> BarcodeFormat.PDF417
            ZxFormat.AZTEC -> BarcodeFormat.AZTEC
            ZxFormat.CODE_128 -> BarcodeFormat.CODE128
            else -> return null
        }
        return Barcode(format, result.text, null)
    }
}
```

- [ ] **Step 4: Run → verify PASS** (3 tests).

Run: `./gradlew :app:testDebugUnitTest --tests "*BarcodeScannerTest*"`
Expected: all pass.
NOTE: `BarcodeRenderer.render` produces an `RGB_565` bitmap. If the round-trip fails to *decode* under Robolectric (a pixel-fidelity issue with 565, not a compile error), do NOT weaken the assertions — instead change the two round-trip tests to build the source bitmap as `ARGB_8888`: render the ZXing `BitMatrix` yourself into an `ARGB_8888` bitmap (black/white pixels) and scan that. Report what you did.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/barcode/BarcodeScanner.kt app/src/test/java/ch/bigli/passes/barcode/BarcodeScannerTest.kt
git commit -m "feat: add BarcodeScanner (ZXing barcode detection from a bitmap)"
```
Append the Co-Authored-By trailer.

---

## Task 2: `PassImporter.displayName` + `PkPassImporter`

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/importing/PassImporter.kt`
- Modify: `app/src/main/java/ch/bigli/passes/importing/PkPassImporter.kt`
- Test: `app/src/test/java/ch/bigli/passes/importing/PkPassImporterTest.kt`

- [ ] **Step 1: Update the failing test first** — in `PkPassImporterTest.kt`, the 3 calls to `importer.import(...)` currently pass 2 args. Add a `displayName` third argument to each. Find each call of the form:
```kotlin
        importer.import(fixture("sample.pkpass").readBytes(), "/data/sample.pkpass")
```
and change it to:
```kotlin
        importer.import(fixture("sample.pkpass").readBytes(), "/data/sample.pkpass", "sample.pkpass")
```
Apply the same to the other two calls (the `notazip.pkpass` and `nopassjson.pkpass` cases): add a third arg equal to the fixture file name string (e.g. `"x.pkpass"` — any non-null String is fine, it's ignored by PkPassImporter).

- [ ] **Step 2: Run → verify FAIL** (2-arg vs 3-arg mismatch: "too few arguments"/signature).

Run: `./gradlew :app:testDebugUnitTest --tests "*PkPassImporterTest*"`
Expected: compile failure until the interface + impl are updated.

- [ ] **Step 3: Update the interface** — replace the contents of `app/src/main/java/ch/bigli/passes/importing/PassImporter.kt` with:
```kotlin
package ch.bigli.passes.importing

import ch.bigli.passes.domain.Pass

/** Converts raw file bytes of one specific format into a domain [Pass]. */
interface PassImporter {
    /**
     * @param rawFilePath where the raw bytes are persisted; stored on the Pass.
     * @param displayName the original file name; used by importers that lack an internal
     *   title (e.g. PDF). Importers with their own title (pkpass) may ignore it.
     */
    fun import(bytes: ByteArray, rawFilePath: String, displayName: String): Pass
}
```

- [ ] **Step 4: Update `PkPassImporter`** — in `app/src/main/java/ch/bigli/passes/importing/PkPassImporter.kt`, change the function signature line:
```kotlin
    override fun import(bytes: ByteArray, rawFilePath: String): Pass {
```
to:
```kotlin
    override fun import(bytes: ByteArray, rawFilePath: String, @Suppress("UNUSED_PARAMETER") displayName: String): Pass {
```
Leave the rest of the method body unchanged (it derives the title from `pass.json`).

- [ ] **Step 5: Fix the repository call site so the project compiles.** In `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`, the call `pkPassImporter.import(bytes, target.absolutePath)` now needs the `displayName`. Change it to:
```kotlin
            pkPassImporter.import(bytes, target.absolutePath, displayName)
```
(The `displayName` parameter is already in scope — it's the `import(bytes, displayName)` function argument. Task 4 restructures this method further; this is the minimal change to keep it compiling now.)

- [ ] **Step 6: Run → verify PASS.**

Run: `./gradlew :app:testDebugUnitTest --tests "*PkPassImporterTest*"`
Expected: the 3 pkpass tests pass.
Run: `./gradlew :app:testDebugUnitTest` (full suite) → all pass (no regression).

- [ ] **Step 7: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/importing/PassImporter.kt app/src/main/java/ch/bigli/passes/importing/PkPassImporter.kt app/src/main/java/ch/bigli/passes/data/PassRepository.kt app/src/test/java/ch/bigli/passes/importing/PkPassImporterTest.kt
git commit -m "refactor: add displayName to PassImporter.import"
```
Append the Co-Authored-By trailer.

---

## Task 3: `PdfImporter`

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/importing/PdfImporter.kt`

No unit test (PdfRenderer is not Robolectric-renderable — verified on-device in Task 5). Its barcode-detection dependency (`BarcodeScanner`) is already tested in Task 1.

- [ ] **Step 1: Implement** — `app/src/main/java/ch/bigli/passes/importing/PdfImporter.kt`
```kotlin
package ch.bigli.passes.importing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import ch.bigli.passes.barcode.BarcodeScanner
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import java.io.File
import java.util.UUID

/** Imports a PDF ticket by rendering its pages and detecting the embedded barcode. */
class PdfImporter(private val scanner: BarcodeScanner = BarcodeScanner()) : PassImporter {

    override fun import(bytes: ByteArray, rawFilePath: String, displayName: String): Pass {
        val barcode = extractBarcode(File(rawFilePath)) ?: throw ImportError.NoBarcode(displayName)
        val title = displayName
            .removeSuffix(".pdf").removeSuffix(".PDF")
            .ifBlank { "PDF ticket" }
        return Pass(
            id = UUID.randomUUID().toString(),
            type = PassType.GENERIC,
            title = title,
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

    /** Renders each page and returns the first detected barcode, or null if none. */
    private fun extractBarcode(file: File): Barcode? {
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            throw ImportError.CorruptFile("cannot open PDF: ${e.message}")
        }
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: Exception) {
            pfd.close()
            throw ImportError.CorruptFile("not a valid PDF: ${e.message}")
        }
        try {
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    val bmp = renderPage(page)
                    val found = scanner.scan(bmp)
                    if (found != null) return found
                }
            }
            return null
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val scale = 2
        var w = page.width * scale
        var h = page.height * scale
        val longest = maxOf(w, h)
        if (longest > MAX_DIMENSION) {
            val factor = MAX_DIMENSION.toDouble() / longest
            w = (w * factor).toInt()
            h = (h * factor).toInt()
        }
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE) // PDFs assume a white background; transparent breaks detection
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }

    private companion object {
        const val MAX_DIMENSION = 2000
    }
}
```

- [ ] **Step 2: Build to verify it compiles.**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/importing/PdfImporter.kt
git commit -m "feat: add PdfImporter (render PDF pages and detect the barcode)"
```
Append the Co-Authored-By trailer.

---

## Task 4: Format detection + repo routing

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/data/PassFormatDetector.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassFormatDetectorTest.kt`
- Modify: `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`

- [ ] **Step 1: Write the failing detector test** — `app/src/test/java/ch/bigli/passes/data/PassFormatDetectorTest.kt`
```kotlin
package ch.bigli.passes.data

import ch.bigli.passes.domain.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassFormatDetectorTest {
    @Test fun `detects a zip (pkpass) by PK magic`() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)
        assertEquals(SourceFormat.PKPASS, detectPassFormat(zip))
    }

    @Test fun `detects a pdf by percent-PDF magic`() {
        val pdf = "%PDF-1.7\n...".toByteArray()
        assertEquals(SourceFormat.PDF, detectPassFormat(pdf))
    }

    @Test fun `returns null for unknown content`() {
        assertNull(detectPassFormat("hello world".toByteArray()))
    }

    @Test fun `returns null for too-short input`() {
        assertNull(detectPassFormat(byteArrayOf(0x25)))
    }
}
```

- [ ] **Step 2: Run → verify FAIL** (`detectPassFormat` unresolved).

Run: `./gradlew :app:testDebugUnitTest --tests "*PassFormatDetectorTest*"`

- [ ] **Step 3: Implement** — `app/src/main/java/ch/bigli/passes/data/PassFormatDetector.kt`
```kotlin
package ch.bigli.passes.data

import ch.bigli.passes.domain.SourceFormat

/**
 * Sniffs the importable format from a file's leading magic bytes.
 * `PK` (0x50 0x4B) → a zip, i.e. a `.pkpass`; `%PDF` → a PDF. Null if neither.
 */
fun detectPassFormat(bytes: ByteArray): SourceFormat? = when {
    bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() ->
        SourceFormat.PKPASS
    bytes.size >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() ->
        SourceFormat.PDF
    else -> null
}
```

- [ ] **Step 4: Run → verify PASS** (4 tests).

Run: `./gradlew :app:testDebugUnitTest --tests "*PassFormatDetectorTest*"`

- [ ] **Step 5: Wire routing into `PassRepository`.** Make three edits to `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`:

(a) Add imports (with the existing imports):
```kotlin
import ch.bigli.passes.domain.SourceFormat
import ch.bigli.passes.importing.PassImporter
import ch.bigli.passes.importing.PdfImporter
```

(b) Change the constructor to inject `PdfImporter` and give both importers defaults:
```kotlin
class PassRepository(
    private val context: Context,
    private val dao: PassDao,
    private val pkPassImporter: PkPassImporter = PkPassImporter(),
    private val pdfImporter: PdfImporter = PdfImporter(),
) {
```

(c) Replace the entire `import(bytes, displayName)` function with:
```kotlin
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
```

(d) Delete the now-unused `isPkPass` private function at the bottom of the class:
```kotlin
    private fun isPkPass(bytes: ByteArray, name: String): Boolean {
        val zipMagic = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        return zipMagic && (name.endsWith(".pkpass", true) || name.endsWith(".zip", true) || true)
    }
```

- [ ] **Step 6: Build + full test suite.**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
Run: `./gradlew :app:testDebugUnitTest` → all pass. In particular the existing `PassRepositoryTest` cases still hold: a real `sample.pkpass` imports (PKPASS branch), and `"hello world"`/`note.txt` → `UnsupportedFormat` (null-format branch).

- [ ] **Step 7: Commit**
```bash
git add app/src/main/java/ch/bigli/passes/data/PassFormatDetector.kt app/src/test/java/ch/bigli/passes/data/PassFormatDetectorTest.kt app/src/main/java/ch/bigli/passes/data/PassRepository.kt
git commit -m "feat: route imports by magic bytes and support PDF"
```
Append the Co-Authored-By trailer.

---

## Task 5: On-device verification

- [ ] **Step 1: Try to generate a test PDF with a barcode** (best effort; skip if no tool available):
```bash
# Prefer a real tool if present; otherwise the user tests with a real PDF ticket.
if command -v qrencode >/dev/null && command -v img2pdf >/dev/null; then
  qrencode -o /tmp/qr.png -s 10 "PDFTEST123" && img2pdf /tmp/qr.png -o /tmp/pdftest.pdf && \
  /home/bigli/Android/Sdk/platform-tools/adb push /tmp/pdftest.pdf /sdcard/Download/pdftest.pdf && echo "pushed /sdcard/Download/pdftest.pdf"
else
  echo "no qrencode/img2pdf; use a real PDF ticket for verification"
fi
```
- [ ] **Step 2: Install** the debug build: `./gradlew :app:installDebug`.
- [ ] **Step 3: Hand off to the user.** Ask the user to:
  - Open the app, tap `+`, and pick a **PDF that contains a barcode** (the generated `pdftest.pdf` in Downloads if it was created, and/or one of their real PDF tickets).
  - Confirm a pass appears titled after the file name, and opening it shows a **scannable barcode** on the detail screen.
  - Pick a **PDF with no barcode** (any random PDF) and confirm a clear "No barcode found" message appears and nothing is added.
  Do NOT capture screenshots; rely on the user's confirmation.
- [ ] **Step 4: Commit** any fixes made during verification (if none, skip).

---

## Self-Review notes

- **Spec coverage:** `BarcodeScanner` detection (Task 1); `displayName` interface change + pkpass update (Task 2); `PdfImporter` render→scan→Pass with NoBarcode/CorruptFile handling (Task 3); magic-byte routing + PDF support (Task 4); device verification incl. the no-barcode path (Task 5). The generic-pass shape (type GENERIC, sourceFormat PDF, title from file name, empty fields, null colors) is built in Task 3.
- **Refinement vs spec:** routing correctness is tested via the pure `detectPassFormat` (Task 4) instead of driving `PdfImporter` through Robolectric, because `PdfRenderer` cannot render under Robolectric. Same behavior, cleaner test.
- **Deferred (unchanged):** PDF page-as-image, text-field parsing, `application/pdf` intent-filter, Google Wallet JSON, manual/scan entry, Phase 4.
- **Type consistency:** `PassImporter.import(bytes, rawFilePath, displayName): Pass` is used by `PkPassImporter`, `PdfImporter`, and the repo; `BarcodeScanner.scan(bitmap): Barcode?`, `detectPassFormat(bytes): SourceFormat?`, and the `PassRepository(context, dao, pkPassImporter, pdfImporter)` constructor (both importers defaulted so existing 3-arg test setups still compile) line up across tasks.
