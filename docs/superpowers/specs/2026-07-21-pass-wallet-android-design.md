# Pass Wallet (Android) — Design

**Date:** 2026-07-21
**Status:** Approved for Phase 1 planning

## Goal

A self-hosted, ad-free Android wallet app to import and display passes (boarding
passes, event tickets, loyalty/coupon cards). Replaces Google Wallet and ad-laden
alternatives. Android-only, personal-use, full local control.

## Non-goals (v1)

- iOS / cross-platform.
- Cloud sync or accounts.
- Creating/issuing passes (import only).
- NFC tap-to-use.

## Tech stack

- Kotlin + Jetpack Compose (native Android).
- Room for the pass index; raw pass files stored on app-internal storage.
- ZXing for barcode rendering.
- Standard libs for zip (pkpass), JSON, PDF parsing. BouncyCastle only in Phase 4
  (signature verification).

## Architecture

One-way funnel: **many importers → one common `Pass` model → one store → one UI.**
Every format is converted into the same `Pass` object, so storage and UI are
format-agnostic. Adding a new format later means writing one importer and touching
nothing else.

```
[.pkpass] [Google JSON] [PDF] [manual QR]
        \      |        |       /
         → PassImporter (per-format parser)
                     ↓
            Pass (common domain model)
                     ↓
         PassRepository  (Room index + raw files on disk)
                     ↓
      Compose UI: PassListScreen → PassDetailScreen
```

## Modules

| Module   | Responsibility | Depends on |
|----------|----------------|------------|
| `domain` | `Pass`, `Barcode`, `PassField`, `PassType`, `SourceFormat`, `UpdateInfo`, `ImportError`. Pure Kotlin, no Android deps. | — |
| `import` | `PassImporter` interface + one impl per format: `PkPassImporter`, `GoogleJsonImporter`, `PdfImporter`, `ManualImporter`. Each maps bytes/input → `Pass`. | domain |
| `data`   | `PassRepository`, Room `PassDao` + entities, copies raw file into app storage. | domain |
| `barcode`| Render `Barcode` → bitmap via ZXing (QR, PDF417, Aztec, Code128). | domain |
| `ui`     | Compose screens + ViewModels: list, detail, import flow. | data, barcode |
| `sync`   | (Phase 4) pkpass web-service polling for updates. | data |

Each unit has one clear purpose, communicates through a well-defined interface, and
is independently testable.

## Domain model

```kotlin
data class Pass(
    val id: String,              // uuid
    val type: PassType,          // BOARDING, EVENT, LOYALTY, COUPON, GENERIC
    val title: String,           // "ZRH → JFK"
    val subtitle: String?,       // "SWISS · Boarding Pass"
    val organization: String?,   // issuer
    val bgColor: Long?,          // from pass.json; fallback per type
    val fgColor: Long?,
    val fields: List<PassField>, // label/value pairs (gate, seat, time…)
    val barcode: Barcode?,
    val relevantDate: Instant?,  // for future sort/relevance
    val rawFilePath: String,     // original file kept for re-render/update
    val sourceFormat: SourceFormat,
    val updateInfo: UpdateInfo?  // webServiceURL + authToken; null if none
)

data class Barcode(val format: BarcodeFormat, val message: String, val altText: String?)
data class PassField(val label: String, val value: String, val position: FieldPosition)

enum class PassType { BOARDING, EVENT, LOYALTY, COUPON, GENERIC }
enum class SourceFormat { PKPASS, GOOGLE_JSON, PDF, MANUAL }
enum class BarcodeFormat { QR, PDF417, AZTEC, CODE128 }
enum class FieldPosition { HEADER, PRIMARY, SECONDARY, AUXILIARY }
```

Every importer fills in this model. pkpass maps cleanly (pass.json uses exactly these
concepts). PDF/manual fill what they can; the rest is null. A pass with only a barcode
is still valid and useful.

## Data flow

**Import:**
1. File arrives via file picker, "Open with" intent, or share sheet.
2. App sniffs type by extension + magic bytes (zip → pkpass, `%PDF` → pdf, `{` → JSON).
3. The matching `PassImporter` parses it into a `Pass`.
4. `PassRepository` copies the raw file to internal storage and inserts the Pass row.
5. List screen refreshes reactively (Flow from Room).

**Display:**
1. `PassListScreen` shows cards colored from each pass's own data, grouped/sorted.
2. Tap → `PassDetailScreen`: render barcode bitmap, show fields, boost screen
   brightness while visible, restore brightness on exit.

## Error handling

- Unknown/corrupt file → importer throws typed `ImportError`
  (`UnsupportedFormat`, `CorruptFile`, `NoBarcode`). UI shows a clear message; nothing
  is imported. Never crash on a malformed file.
- PDF with no detectable barcode → surface "no barcode found," offer manual entry.
- Partial pkpass (missing images/fields) → import anyway with fallbacks.
- Signature verification failure (Phase 4) → import but flag "unverified"; never block.

## Testing (TDD)

- `domain` + `import`: pure unit tests against fixture files in test resources
  (`sample.pkpass`, `ticket.pdf`, `google-pass.json`) asserting the produced `Pass`.
  This is the core, most-tested layer.
- `barcode`: assert bitmap is non-null with correct dimensions per format.
- `data`: Room in-memory DB tests for save/load/delete.
- `ui`: ViewModel unit tests; Compose UI tests for list + detail.

## Phase plan

| Phase | Scope |
|-------|-------|
| **1 — Core** | `domain` + Room + `PkPassImporter` + list/detail UI + ZXing barcode + max-brightness + file picker. A usable single-format wallet. |
| **2 — Easy import** | "Open with" file/MIME intent + share-sheet import for .pkpass. |
| **3 — More formats** | `PdfImporter`, `ManualImporter`, `GoogleJsonImporter`. |
| **4 — Updates + trust** | pkpass web-service auto-update polling; Apple signature verification (flag-only, non-blocking). |

The first implementation plan covers **Phase 1 only**. Each later phase gets its own
spec → plan → implementation cycle.

## UI reference

Two primary screens (mockup validated during brainstorming):
- **Wallet Home:** scrollable list of colored pass cards, "＋" to import.
- **Pass Detail:** full-screen pass styled from its own colors, barcode rendered large
  on a white card with alt-text below, brightness boosted for scanning.
