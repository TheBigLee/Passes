# PDF Ticket Import — Design

**Date:** 2026-07-21
**Status:** Approved for planning
**Phase:** 3 (first of the "more import formats" set; Google Wallet JSON and manual/scan entry are separate future specs)

## Goal

Import a PDF ticket/boarding pass by detecting its embedded barcode and storing it as a
scannable pass. The barcode is the valuable payload (it's what a gate scanner reads); PDFs
are otherwise unstructured, so we do not attempt to parse arbitrary text fields.

## Scope

- **In:** detect a barcode from a PDF's rendered page(s); create a `Pass` (generic type)
  with that barcode and a title from the file name.
- **Out (deferred):** rendering the PDF page as the pass image; parsing text into fields;
  an `application/pdf` "Open with"/share intent-filter (we don't want Passes offered for
  every PDF on the device — PDFs are imported via the in-app `+` picker only).

## Architecture

**Flow:** `+` picker → `PassRepository` sniffs magic bytes → `PdfImporter` renders the
page(s) with `android.graphics.pdf.PdfRenderer` → `BarcodeScanner` detects the barcode with
ZXing → build a `Pass`.

### New unit — `BarcodeScanner` (`barcode/BarcodeScanner.kt`)

The **testable core**: detects a barcode in a bitmap.

```kotlin
class BarcodeScanner {
    /** Detects the first supported barcode in [bitmap], or null if none is found. */
    fun scan(bitmap: Bitmap): Barcode?
}
```

- Reads pixels via `bitmap.getPixels(...)` into a `com.google.zxing.RGBLuminanceSource`,
  wraps in `HybridBinarizer` → `BinaryBitmap`, decodes with `MultiFormatReader`.
- Hints: `POSSIBLE_FORMATS = [QR_CODE, PDF_417, AZTEC, CODE_128]`, `TRY_HARDER = true`.
- Maps the ZXing `BarcodeFormat` back to our domain `BarcodeFormat`; `altText = null`.
- `NotFoundException` (or any decode failure) → returns `null` (never throws).

### New unit — `PdfImporter` (`importing/PdfImporter.kt`)

Implements `PassImporter`. Opens the saved file with `PdfRenderer` (via
`ParcelFileDescriptor.open(file, MODE_READ_ONLY)`), renders each page to an
`ARGB_8888` bitmap on a white background at ~2× the page size (longest side capped at
2000 px), and scans pages in order until `BarcodeScanner` finds a barcode.

- First barcode found → build the `Pass`.
- No barcode on any page → `ImportError.NoBarcode`.
- File is not a valid/renderable PDF (PdfRenderer throws) → `ImportError.CorruptFile`.
- Always closes each `PdfRenderer.Page`, the `PdfRenderer`, and the `ParcelFileDescriptor`.

Constructed with an injectable scanner for testability: `PdfImporter(scanner: BarcodeScanner = BarcodeScanner())`. Needs no `Context` (PdfRenderer works from a file descriptor).

### Interface change — `PassImporter`

Add a `displayName` parameter so importers that lack an internal title (PDF) can use the
original file name:

```kotlin
interface PassImporter {
    fun import(bytes: ByteArray, rawFilePath: String, displayName: String): Pass
}
```

- `PkPassImporter` accepts and **ignores** `displayName` (still titles from `pass.json`).
- `PdfImporter` uses `displayName`.

### Repo routing — `PassRepository`

Replace the pkpass-only sniffing with magic-byte detection and inject the PDF importer:

```kotlin
class PassRepository(
    context: Context,
    dao: PassDao,
    pkPassImporter: PkPassImporter = PkPassImporter(),
    pdfImporter: PdfImporter = PdfImporter(),
)
```

`import(bytes, displayName)`:
1. Detect format from magic bytes: leading `PK` (0x50 0x4B) → PKPASS; leading `%PDF`
   (0x25 0x50 0x44 0x46) → PDF; otherwise throw `ImportError.UnsupportedFormat`.
2. Save the raw bytes to `filesDir/passes/<uuid>.<ext>` (`.pkpass` or `.pdf`).
3. Call the matching importer's `import(bytes, savedPath, displayName)`; on any throw,
   delete the saved file and rethrow.
4. Insert the resulting `Pass`.

`importFromUri` / `importFromUrl` are unchanged — they already funnel through
`import(bytes, displayName)`.

## Domain / model

No change. PDF passes are built as: `type = GENERIC`, `sourceFormat = PDF`,
`title = displayName` with a trailing `.pdf`/`.PDF` stripped (falls back to "PDF ticket" if
blank), `subtitle = null`, `organization = null`, `bgColor/fgColor = null` (UI defaults),
`fields = []`, `barcode =` the detected barcode, `relevantDate = null`, `updateInfo = null`.

## UI

No change. The `+` picker already accepts any file (`OpenDocument(arrayOf("*/*"))`), and the
repository routes by content. Import errors (`NoBarcode`, `CorruptFile`, `UnsupportedFormat`)
surface through the existing snackbar/Toast path. The imported pass renders on the existing
list/detail screens (generic card, barcode on detail).

## Error handling

- Not a PDF or pkpass → `UnsupportedFormat` (clear message, nothing imported).
- Corrupt/unrenderable PDF → `CorruptFile`.
- PDF with no detectable barcode → `NoBarcode` ("No barcode found") — surfaced to the user.
- All rendering/decoding failures are caught; the app never crashes on a bad file.

## Testing

- **`BarcodeScannerTest`** (Robolectric, real round-trip): render a known payload with the
  existing `BarcodeRenderer`, then `scan()` it back and assert the message + mapped format.
  Cases: QR ("HELLO"), PDF417 ("M1SWISS ZRHJFK"), Code128 ("12345678"); a blank white bitmap
  → `null`. This exercises ZXing detection end-to-end without image files.
- **`PassRepository` routing** (Robolectric): importing `%PDF`-prefixed bytes that are not a
  real renderable PDF throws `ImportError.CorruptFile` (proves it routed to the PDF importer,
  which then failed to render), and importing random text throws
  `ImportError.UnsupportedFormat`. Existing pkpass import tests still pass.
- **`PkPassImporterTest`**: update its 3 `import(...)` calls for the new `displayName`
  parameter (e.g. pass `"sample.pkpass"`); behavior unchanged.
- **`PdfImporter` end-to-end** is **not** unit-tested — `PdfRenderer` is a framework class
  Robolectric cannot render. It is verified on-device (below).
- **Device verification:** import a PDF containing a barcode via the `+` picker and confirm a
  pass appears with a scannable barcode on the detail screen; also try one real PDF ticket.
  (Build + install automated; the user confirms — no screenshots.)

## Files

| File | Change |
|------|--------|
| `barcode/BarcodeScanner.kt` | New — ZXing detection from a Bitmap |
| `test/.../barcode/BarcodeScannerTest.kt` | New — round-trip tests |
| `importing/PassImporter.kt` | Modify — add `displayName` param |
| `importing/PkPassImporter.kt` | Modify — accept + ignore `displayName` |
| `test/.../importing/PkPassImporterTest.kt` | Modify — update 3 call sites |
| `importing/PdfImporter.kt` | New — PdfRenderer → BarcodeScanner → Pass |
| `data/PassRepository.kt` | Modify — magic-byte routing + inject `PdfImporter` |
| `test/.../data/PassRepositoryTest.kt` | Modify — add PDF-routing tests |

## Task breakdown (subagent-driven)

1. `BarcodeScanner` + round-trip tests (TDD).
2. Extend `PassImporter` with `displayName`; update `PkPassImporter` + its tests.
3. `PdfImporter` (PdfRenderer → BarcodeScanner → Pass).
4. `PassRepository` magic-byte routing (`%PDF` → PdfImporter) + routing tests.
5. Device verification with a generated and a real PDF.
