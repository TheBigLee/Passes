# Structured Manual Pass Form Design

**Issue:** [#10](https://github.com/TheBigLee/Passes/issues/10) - Structured form when adding a plain barcode/QR code

## Problem

The `MANUAL` pass creation flow (manual entry or camera scan, via `CreatePassScreen`) only
asks for a barcode value and format. Since the pass's synthesized `title` field was removed
in an earlier release, manually-created passes carry no identifying text at all - no name,
no date, nothing to distinguish one from another in the pass list.

## Scope

This covers only the `MANUAL` create flow (`CreatePassScreen` / `PassRepository.createManualPass`).
PDF-imported passes (`PdfImporter`) are out of scope - they already extract what they can
from the PDF, and a richer PDF-side flow would be a separate issue if ever needed.

## Architecture

`CreatePassScreen.kt` gains a kind picker (segmented buttons: Event / Boarding / Loyalty /
Generic) at the top, followed by a common **Organization** text field, then kind-specific
fields rendered by a new `ManualPassFields.kt` file - one composable per kind, mirroring how
`PassFieldLayouts.kt` is already split out from `PassDetailScreen.kt`. This keeps
`CreatePassScreen.kt` itself limited to the scaffold, top-level state, and submit wiring.

The existing barcode value + format fields stay at the bottom of the form, unchanged.

Date/time fields are read-only `OutlinedTextField`s that open a Material3 `DatePickerDialog`
/ `TimePickerDialog` on tap, rather than free-text entry - avoids free-text date parsing bugs
and gives a proper mobile picker UX.

`PassRepository.createManualPass` gains parameters so it builds the same `Pass` shape that
imported passes use, meaning manually-created passes render through the exact same
`BoardingFieldsLayout` / `GenericFieldsLayout` as imported ones - no separate rendering path.

New signature:

```kotlin
suspend fun createManualPass(
    type: PassType,
    organization: String,
    fields: List<PassField>,
    relevantDate: Instant?,
    transitType: TransitType?,
    barcodeFormat: BarcodeFormat,
    barcodeValue: String,
): Pass
```

Internally still builds a `Pass` with `sourceFormat = SourceFormat.MANUAL`,
`updateInfo = null`, `rawFilePath = ""`, `bgColor`/`fgColor = null` (default color - no color
picker, keeping this a lightweight form rather than a full pass-authoring tool), same as today.

## Fields per kind

All kinds share one required **Organization** field, mapped to both `pass.organization` and
`pass.subtitle` (shown in the top app bar - e.g. "SWISS", "Coop"), plus the existing barcode
value/format fields at the bottom of the form.

| Kind | Fields | Maps to |
|---|---|---|
| **Event** | Event name (required) · Location (optional) · Date (optional) · Time (optional) | Event name -> PRIMARY field, label "Event"; Location -> SECONDARY field, label "Location"; Date -> AUXILIARY field, label "Date"; Time -> AUXILIARY field, label "Time"; Date+Time combine into `relevantDate` |
| **Boarding** | From (required) · To (required) · Transit mode (Air/Bus/Train/Boat/Generic, default Generic) · Date (optional) · Time (optional) | From -> PRIMARY field, label "Departure"; To -> PRIMARY field, label "Arrival"; Transit mode -> `pass.transitType`; Date -> AUXILIARY field, label "Date"; Time -> AUXILIARY field, label "Boards"; Date+Time combine into `relevantDate` |
| **Loyalty** | Member name (optional) · Details (optional free text, e.g. balance/points) | Member name -> AUXILIARY field, label "Member"; Details -> AUXILIARY field, label "Details" |
| **Generic** | Description (optional) · Date (optional) · Time (optional) | Description -> PRIMARY field, label "Info"; Date -> AUXILIARY field, label "Date"; Time -> AUXILIARY field, label "Time"; Date+Time combine into `relevantDate` |

Boarding's From/To are both required (non-blank): `BoardingFieldsLayout` only renders its big
two-up departure/arrival row when there are exactly 2 PRIMARY fields, so leaving one blank
would produce a broken-looking layout. The Create button stays disabled until both are filled
(same pattern as the existing barcode-value requirement).

Switching the kind picker keeps each kind's own draft in memory rather than discarding it -
`CreatePassScreen` holds one remembered draft per kind, so switching Event -> Boarding -> back
to Event restores whatever was typed into the Event fields. The fields still don't share meaning
*across* kinds (nothing is copied between drafts), but nothing already typed is lost either.

## Data flow & validation

- Date + time, when both are given, combine via `LocalDate` + `LocalTime` +
  `ZoneId.systemDefault()` into an `Instant` for `relevantDate`.
- If only one of date/time is given, it's still shown as a display field (rendered into its
  AUXILIARY `PassField`) but does not set `relevantDate` - there's no sensible way to guess a
  fake time-of-day or date to fill the gap.
- If neither is given, `relevantDate` stays `null` (existing list-sort behavior already sorts
  null-date passes last, via `PassDao.observeAll`'s `ORDER BY relevantDateEpoch IS NULL, ...`).
- The Create button is disabled until required fields for the currently-selected kind are
  filled: Organization + barcode value always; Event name additionally for Event; From + To
  additionally for Boarding.
- A background color picker (a row of curated swatches plus a "default" option) was added to
  the form after this design was first written, setting `pass.bgColor`. There's no separate
  foreground-color picker - `legibleTextColor` (already used for imported passes) derives a
  legible foreground from whichever background is chosen, at render time.

## Testing

- `PassRepositoryManualTest.kt` gets new cases per kind: the expected `PassField` list and
  `FieldPosition`s are built correctly, `relevantDate` combines correctly when both date+time
  are given vs. only one vs. neither, and Boarding's `transitType` round-trips through
  `createManualPass` into the stored `Pass`.
- No new Compose UI tests - this project has no Compose UI test infra set up today (existing
  `CreatePassScreen` has none either). Verified manually on-device per kind instead.
