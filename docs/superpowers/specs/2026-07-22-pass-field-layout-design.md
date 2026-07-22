# Pass Field Layout Redesign — Design

**Goal:** Fix GitHub issues [#12](https://github.com/TheBigLee/Passes/issues/12) (primary field values never shown) and [#13](https://github.com/TheBigLee/Passes/issues/13) (non-primary fields silently truncated past 4) by rendering `Pass.fields` according to their actual `FieldPosition`, instead of pooling everything but `PRIMARY` into one `take(4)` row.

## Background

`PassDetailScreen.kt` and `PassListScreen.kt` both do:

```kotlin
pass.fields.filter { it.position != FieldPosition.PRIMARY }.take(4)
```

`FieldPosition` already models `HEADER`, `PRIMARY`, `SECONDARY`, `AUXILIARY`, `BACK` (`domain/Pass.kt`) — the bug is purely in the rendering layer, which never distinguishes these categories from each other and never renders `PRIMARY` at all.

Two real Wallet references were captured live on-device (2026-07-22) to ground this design:

- **Boarding pass (Google Wallet, SWISS LX1352):** header row = logo + small top-right callout; big `DEPARTURE`/`ARRIVAL` row with airport codes (`ZRH ✈ WAW`) and a plane icon between; a `FLIGHT`/`DATE`/`BOARDING`/`CLASS` row; a `PASSENGER`/`STATUS` row; barcode below.
- **Event pass (Google Wallet, CloudNative Zürich 2025):** no grid at all — each field stacks full-width, label (small caps) above value (large) for the top fields (`EVENT`, `PRODUCT`), then paired two-up rows for the rest (`ATTENDEE NAME` + `FROM`). No count cap; scrolls.

Cross-checked against this repo's own sample pkpasses (`sample-passes/*.pkpass`):

```
boarding-air.pkpass:
  headerFields:    BOARDS: 08:45
  primaryFields:   origin{label=JFK, value=New York}, dest{label=JFK, value=New York}
  secondaryFields: GATE: A12, SEAT: 14C
  auxiliaryFields: DATE: 15 Aug, CLASS: Economy

event-ticket.pkpass:
  headerFields:    DOORS: 18:30
  primaryFields:   EVENT: Basel Tattoo
  secondaryFields: DATE: 18 Jul 2026, TIME: 20:00
  auxiliaryFields: SECTION: B, ROW: 12, SEAT: 5
```

**Non-obvious finding:** for `boardingPass` primary fields, `label` holds the airport code and `value` holds the city name — the inverse of every other field position, where `label` is the small caption and `value` is the main content. Real Wallet apps render the code big and the city name small beneath it, i.e. **label big / value small** for boarding primaries specifically.

`headerFields` are empty in 3 of 5 sample pass types (`coupon`, `storeCard`, `generic`) and, where present, hold a single "key time" callout (boarding time / doors-open time). Handling `HEADER` fields for non-boarding pass types is **out of scope for this iteration** — deferred to a later pass.

## Scope

- **BOARDING** gets a dedicated layout matching the SWISS reference: top-right header slot, big two-up primary row (label-big/value-small, plane icon), then SECONDARY and AUXILIARY each as their own row.
- **EVENT, LOYALTY, COUPON, GENERIC** share one generic layout matching the CloudNative Zürich reference: PRIMARY fields stacked full-width and large, then SECONDARY and AUXILIARY fields in two-per-row groups. No field is dropped. `HEADER` is not rendered for these types in this iteration.
- No changes to `Pass`, `PassField`, or `FieldPosition` — this is a rendering-layer fix only.

## Architecture

Change is scoped to the fields-column region of `PassFrontContent` in `PassDetailScreen.kt` (currently lines ~434-442) and the summary line in `PassListScreen.kt` (currently line ~161). Everything else in `PassFrontContent` — strip image, pull-to-refresh, barcode rendering, voided/expired dimming — is unchanged and shared.

**`PassFrontContent`** forks the fields-column rendering on pass type:

```kotlin
if (pass.type == PassType.BOARDING) {
    BoardingFieldsLayout(pass, fg)
} else {
    GenericFieldsLayout(pass, fg)
}
```

The `HEADER` top-right slot for boarding passes is lifted out of the scrollable fields column into the top-of-card area (near the existing `strip` image), since it needs to sit next to the logo/strip rather than scroll with the rest of the fields.

### `GenericFieldsLayout(pass, fg)`

- Renders every `PRIMARY` field full-width: label small-caps (10sp, `fg.copy(alpha = 0.7f)`) above value large (20sp, `FontWeight.Medium`).
- Renders every `SECONDARY` field, then every `AUXILIARY` field, in that order, grouped two-per-row via `FlowRow` (label 10sp above value 14sp, matching today's existing per-field text styles) — odd counts leave the last row's single item left-aligned rather than centered/stretched.
- No `take(N)` anywhere — every field present renders. Column is already `verticalScroll`, so overflow just scrolls.
- `HEADER` fields are not rendered by this layout in this iteration.

### `BoardingFieldsLayout(pass, fg)`

- Caller (in the top-of-card area) renders `HEADER` fields, if any, in a small row aligned top-right next to the logo/strip.
- Takes the first 2 `PRIMARY` fields (Apple's boardingPass spec caps this at 2) and renders them as a big two-up row with a plane icon between: **label** big (28sp+, matches the airport-code-big convention), **value** small caption beneath (matches city-name-small) — the inverse of the label/value sizing used everywhere else. If there are 0 primary fields, this row is skipped entirely (no empty icon row). If there are more than 2, the rest are silently dropped (matches the one field group Apple's own spec bounds).
- Renders `SECONDARY` fields as an evenly-spaced row (today's existing per-field style: label 10sp / value 14sp Medium).
- Renders `AUXILIARY` fields as a second row below, same style.
- No cap on `SECONDARY`/`AUXILIARY` count — wraps via `FlowRow` instead of being dropped.

## Error Handling

- BOARDING with 0 `PRIMARY` fields: skip the big row, no empty icon row rendered.
- BOARDING with >2 `PRIMARY` fields: render first 2, drop the rest silently (matches Apple's spec-level cap on this one field group — not a new silent-drop bug, since the spec itself bounds this list to 2).
- Empty `SECONDARY`/`AUXILIARY` lists (either layout): row doesn't render — natural behavior of iterating an empty list, no special-casing needed.
- `GenericFieldsLayout` has no field-count guard at all — always stacks whatever's present.

## Testing

- No Compose UI test harness exists in this repo currently — verification is manual, on-device, using the real sample pkpasses already checked into `sample-passes/` (`boarding-air.pkpass` for the boarding layout, `event-ticket.pkpass` for the generic layout), confirming all positioned fields render with nothing dropped.
- Any pure logic extracted (e.g. a helper grouping `List<PassField>` by `FieldPosition`, or the "first 2 primaries" selection) is a plain function and gets a unit test in `app/src/test`, following the existing pattern in `BarcodeRendererTest.kt`.
