# Manual / Scan Barcode Entry — Design

**Date:** 2026-07-21
**Status:** Approved for planning
**Phase:** 3 (completes the "more formats" set alongside PDF; Google Wallet JSON remains).

## Goal

Let the user add a pass by **scanning** a physical card's barcode with the camera, or by
**typing** the value manually — the "digitize my loyalty cards" flow. Both produce a
`MANUAL`-source generic pass with a title and a scannable barcode.

## Scope

- **In:** camera scan (CameraX + ZXing) and manual entry, both funneling into one create
  form (title + value + format); create a `MANUAL` pass.
- **Out (deferred):** batch/multi scan; editing the barcode value after creation (title is
  editable via the existing rename feature); Google Wallet JSON.

## Architecture

**The `+` becomes a menu.** Today the FAB opens the file picker directly. It now opens a
`ModalBottomSheet` with three actions: **Import file** (existing picker), **Scan barcode**,
**Enter manually**.

### Camera scanning (CameraX + ZXing)

- **`barcode/CameraBarcodeAnalyzer.kt`** — an `ImageAnalysis.Analyzer`. For each frame it reads
  the Y (luminance) plane into a `com.google.zxing.PlanarYUVLuminanceSource`, wraps in
  `HybridBinarizer` → `BinaryBitmap`, and decodes with `MultiFormatReader` using the shared
  hints (formats QR/PDF417/Aztec/Code128, `TRY_HARDER`). On the first successful decode it
  invokes a callback with the domain `Barcode` and stops delivering further results. Always
  closes the `ImageProxy`.
- **Shared mapping:** extract the ZXing→domain format mapping and hint set used by
  `BarcodeScanner` into small shared helpers in the `barcode` package (`ZxFormat.toDomain()` +
  `zxingHints()`), reused by both `BarcodeScanner` and `CameraBarcodeAnalyzer` (DRY).
- **`ui/ScanScreen.kt`** — Compose screen: requests the `CAMERA` permission at runtime; when
  granted, shows a CameraX `PreviewView` (via `AndroidView`) bound to the composable lifecycle
  with a `Preview` + `ImageAnalysis(CameraBarcodeAnalyzer)`. On a detected barcode it stores the
  result and navigates to the create form. If permission is denied, shows a short explanation
  and a Back action.

### Create form (scan + manual share it)

- **`ui/CreatePassScreen.kt`** — a form with **Title** (text), **Barcode value** (text), and
  **Format** (dropdown: QR / PDF417 / Aztec / Code128). **Save** is enabled only when title and
  value are non-blank. When reached from a scan, value + format are pre-filled (and the value
  field is still editable).
- **Passing the scanned result:** a scanned barcode can contain slashes/control chars, so it is
  NOT put in a nav route. Instead `PassApp` holds `pendingScan: MutableStateFlow<Barcode?>`; the
  scan screen sets it and navigates to `create`; the create screen reads it once as the prefill
  and clears it. Manual entry navigates to `create` with `pendingScan == null` (empty form).

### Creating the pass

- **`PassRepository.createManualPass(title: String, format: BarcodeFormat, value: String): Pass`**
  (on `Dispatchers.IO`) builds:
  `Pass(id = UUID, type = GENERIC, title = title.trim(), subtitle = null, organization = null,
  bgColor = null, fgColor = null, fields = emptyList(), barcode = Barcode(format, value, null),
  relevantDate = null, rawFilePath = "", sourceFormat = MANUAL, updateInfo = null)`, inserts it,
  and returns it.
- **No source file:** `rawFilePath = ""`. This is safe with existing code — `delete` does
  `File("").delete()` (harmless) and `PassImageLoader.load("")` opens `ZipFile("")` which throws
  and is caught → returns `null` (no logo/strip). The pass renders as a plain generic card
  (default blue bg, white legible text) with its barcode on the detail screen.
- After create, navigate to the new pass's detail (`detail/{id}`), popping the scan/create
  screens off the back stack.

## Navigation

New routes in `AppNav`: `scan` and `create`. The `+` bottom sheet routes to `scan`,
`create`, or launches the file picker. The create screen's Save → `detail/{id}` with
`popUpTo("list")` so Back returns to the list, not the form.

## Permissions & dependencies

- Manifest: `<uses-permission android:name="android.permission.CAMERA" />` and
  `<uses-feature android:name="android.hardware.camera.any" android:required="false" />`.
- CameraX (latest stable 1.4.x): `androidx.camera:camera-core`, `camera-camera2`,
  `camera-lifecycle`, `camera-view` — added to the version catalog.

## Error handling

- Camera permission denied → in-screen message + Back (no crash).
- No camera / bind failure → message; the user can still use manual entry.
- Blank title or value → Save disabled.
- Manual pass with an unusual value → stored verbatim; rendering/scanning is best-effort
  (ZXing may fail to *render* an invalid Code128 payload, but that's the user's input).

## Testing

- **`PassRepository.createManualPass`** (Robolectric, in-memory Room): create → `getById`
  returns a pass with `type == GENERIC`, `sourceFormat == MANUAL`, the given title/format/value,
  and `rawFilePath == ""`; it also appears in `observeAll()`.
- **Barcode decoding** is already covered by the existing `BarcodeScanner` round-trip test; the
  shared `toDomain()`/`zxingHints()` helpers are exercised there.
- **Camera scan, permission flow, the create form, and the `+` menu** are device-verified
  (CameraX/permission can't be unit-tested). Build + install automated; the user confirms — no
  screenshots.

## Files

| File | Change |
|------|--------|
| `gradle/libs.versions.toml` | New — CameraX deps |
| `app/build.gradle.kts` | New — CameraX dependencies |
| `app/src/main/AndroidManifest.xml` | New — CAMERA permission + camera feature |
| `barcode/BarcodeScanner.kt` | Modify — use shared `toDomain()`/`zxingHints()` |
| `barcode/ZxingSupport.kt` | New — shared format mapping + hints |
| `barcode/CameraBarcodeAnalyzer.kt` | New — CameraX analyzer (ZXing over Y-plane) |
| `data/PassRepository.kt` | New — `createManualPass` |
| `PassApp.kt` | New — `pendingScan` holder |
| `ui/ScanScreen.kt` | New — CameraX preview + permission |
| `ui/CreatePassScreen.kt` | New — the create/manual form |
| `ui/PassListScreen.kt` | Modify — `+` bottom-sheet menu |
| `MainActivity.kt` | Modify — `scan` + `create` routes; menu wiring |
| `test/.../data/PassRepositoryManualTest.kt` | New — `createManualPass` round-trip |

## Task breakdown (subagent-driven)

1. `PassRepository.createManualPass` + Room test.
2. CameraX deps + manifest CAMERA permission + shared `ZxingSupport` + `CameraBarcodeAnalyzer`.
3. `ScanScreen` (CameraX preview + permission handling).
4. `CreatePassScreen` (form) + `pendingScan` holder.
5. `+` bottom-sheet menu + `scan`/`create` nav wiring.
6. Device verification (scan a real card, manual entry, permission-denied).
