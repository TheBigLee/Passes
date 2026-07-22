# Detail Screen Redesign + pkpass backFields — Design

## Goal

Started as "support pkpass `backFields`" (the back-of-pass info Apple Wallet passes can carry)
but grew, through discussion, into a broader redesign: `Pass.title` is a synthesized value (not
an actual Apple pkpass field) that duplicates what the logo/fields already show, so it's removed
entirely — along with the rename feature that existed solely to edit it. Delete and (now-gone)
Edit move off the detail screen's front into a new flip-to-back view, which is also where
`backFields` content lives.

## Scope

**In scope:** removing `Pass.title`/`computeTitle`/rename entirely; adding `Pass.backFields`;
redesigning `PassDetailScreen` with a persistent flip-to-back affordance; updating
`PassListScreen`'s row to drop its title line.

**Explicitly out of scope / deferred:** manually-created and PDF-imported passes have no
organization/subtitle data today, so removing `title` leaves them with only a generic type-name
fallback (e.g. "GENERIC") in the list — acknowledged as a real, temporary UX regression for those
two source formats. The planned fix is the already-backlogged "structured form when adding a
plain barcode/QR code" (`docs/ideas.md`), which will ask for a pass "kind" up front and collect
kind-appropriate identifying fields — not solved here.

## Data model

- `Pass.title: String` and `Pass.titleCustomized: Boolean` are removed. `computeTitle()` (domain
  function) is deleted.
- `Pass.backFields: List<PassField> = emptyList()` is added — same `PassField(label, value,
  position)` type as the existing field lists (reusing it keeps serialization/translation code
  identical), stored as its own list rather than folded into `fields`. `FieldPosition` gains a new
  `BACK` value used only by entries in this list, so the data is self-documenting rather than
  reusing an unrelated front-layout position as a placeholder. Nothing filters on `FieldPosition`
  for `backFields` (the whole list is always rendered on the back), so `BACK` exists purely for
  clarity, not because any code branches on it today. Only ever populated for
  `SourceFormat.PKPASS`; always empty for `MANUAL`/`PDF`.
- `PassEntity` mirrors: drops `title`, `titleCustomized`; adds `backFieldsJson: String`
  (serialized the same way `fieldsJson` already is).

## Migration (v4 → v5)

SQLite's native `ALTER TABLE ... DROP COLUMN` (added in SQLite 3.35) isn't safely available across
this app's full `minSdk 26` device range, since Android's bundled SQLite version varies by OS
version. `MIGRATION_4_5` instead uses the standard portable rebuild pattern: create a new `passes`
table without `title`/`titleCustomized`, copy every other column across via `INSERT INTO ... 
SELECT`, drop the old table, rename the new one. `backFieldsJson` is added as part of the same
new-table definition (defaulting existing rows to `'[]'`).

`PassDao.observeAll`'s `ORDER BY relevantDateEpoch IS NULL, relevantDateEpoch ASC, title ASC`
changes its tiebreaker to `subtitle ASC` (title no longer exists to sort by).

## Importing

- `PkPassJson`/`PkPassImporter`: `PkStructure` gains `backFields: List<PkField>? = null`, parsed
  into `Pass.backFields` via the existing `toField(FieldPosition.BACK)` helper — same pattern as
  the other field groups, just targeting the new list and position.
- `PkPassImporter` no longer computes or sets a title.
- `PdfImporter` no longer sets a title (previously derived from the PDF's filename) — its passes
  join manually-created passes in the "no identifying text yet" deferred bucket above.
- `PassRepository.createManualPass(format: BarcodeFormat, value: String): Pass` — drops its
  `title` parameter entirely.

## Repository

- `PassRepository.updateTitle()` and the corresponding `PassDao.updateTitle` query are deleted.
- The live-translation decorator (`localize()`) drops its title-recompute branch entirely (no
  more `computeTitle`/`titleCustomized` check) — it still translates `fields`, `organization`,
  `subtitle`, `description`, and now also `backFields` (same label/value translation as the other
  field lists).
- `refreshPass()`'s merged-`Pass` construction drops the `title`/`titleCustomized` lines it
  previously preserved across a refresh.

## UI

- **`CreatePassScreen`**: the "title" text field is removed. `onCreate` callback signature becomes
  `(format: BarcodeFormat, value: String) -> Unit`.
- **`PassListScreen`**: `PassCard` drops its title `Text`. The existing smaller
  `(subtitle ?: organization ?: type.name).uppercase()` line and the fields-summary line stay as
  they are — this is what carries identification now.
- **`PassDetailScreen`**: drops the title `Text`, the pencil (edit) and trash (delete) icons from
  the top app bar, `TitleEditDialog`, and all `showEditDialog`/`openTitleEditor` state. Gains:
  - A persistent circular info icon, floating bottom-right over the card content, on every pass
    regardless of source format (it's the only path to Delete now, so it can't be conditional on
    `backFields` being present).
  - Tapping it triggers a 3D flip (Compose `Modifier.graphicsLayer { rotationY = ...; cameraDistance
    = ... }` animated via `Animatable`/`animateFloatAsState`, the standard "card flip" recipe) from
    the front content to a "back" composable — same pass background color, showing `backFields` as
    label/value pairs (or nothing, just the background, if the list is empty), plus a Delete button.
    The back content is counter-rotated 180° so it reads normally once the flip completes, rather
    than appearing mirrored.
  - Tapping the same icon (same corner, now showing a "flip back" state) reverses the animation
    back to the front.
- **`PassApp.kt`/`MainActivity.kt`**: `PendingPass.editTitle` and the `detail/{id}?editTitle={editTitle}`
  nav route argument are removed — `PendingPass` becomes just `id: String`, and the route becomes
  plain `detail/{id}`.
- **`PassDetailViewModel`**: `updateTitle()` removed.

## Testing

- Domain: remove `computeTitle` tests from `PassTest.kt` (delete, don't adapt — the function is
  gone). Add coverage for `Pass.backFields` default/round-trip through `PassEntity`.
- `PkPassImporterTest`: remove title-related assertions (including the two added in the prior
  localization work — primary-field-label-fallback existed *only* to feed `computeTitle`, so once
  title's gone that specific fallback behavior has no remaining consumer and its test goes with
  it); add backFields-parsing tests.
- `PassDatabaseMigration4Test` (new, following the existing hand-built-schema pattern): v4 schema
  built by hand (matching what Room actually generated for v4 — verify via the established
  temporary-`exportSchema=true` technique rather than guessing), migrate to v5, confirm `title`/
  `titleCustomized` are gone, `backFieldsJson` defaults to `'[]'`, all other columns/data survive.
- `PassRepositoryLocalizationTest`/`PassRepositoryRefreshTest`/`PassRepositoryUpdateTest`: remove
  every title/titleCustomized-specific test; `PassRepositoryUpdateTest` likely disappears entirely
  (it only tested `updateTitle`). Add a `backFields` live-translation test alongside the existing
  field-translation ones.
- `PassRepositoryManualTest`/`CreatePassScreen`-adjacent tests and `MainActivity`/nav wiring:
  update call sites for the new `createManualPass(format, value)` signature and the simplified
  `PendingPass`/route.
- `PassDetailViewModelTest`: remove `updateTitle` test; add coverage for whatever state the
  flip/back UI needs from the ViewModel (see plan for exact shape — likely just exposing
  `pass.backFields` and `pass.sourceFormat`/existing `pass` flow, no new ViewModel state expected
  since flip is purely a Compose UI concern).
- Device verification: confirm the flip animation looks right on a real device, confirm Delete
  still works from the back, confirm a real backFields-bearing pkpass renders correctly on the
  back, confirm existing (pre-migration) passes open without crashing and show reasonable
  (if degraded, for manual/PDF) content.

## Files touched

| File | Change |
|---|---|
| `domain/Pass.kt` | Remove `title`, `titleCustomized`, `computeTitle`; add `backFields` |
| `data/PassEntity.kt` | Mirror: drop `title`/`titleCustomized` columns, add `backFieldsJson` |
| `data/PassDatabase.kt` | `MIGRATION_4_5` (table rebuild + backFieldsJson), version bump to 5 |
| `data/PassDatabaseMigration4Test.kt` | New |
| `data/PassDao.kt` | Remove `updateTitle` query; change `observeAll` sort tiebreaker |
| `importing/PkPassJson.kt` | `PkStructure.backFields` |
| `importing/PkPassImporter.kt` | Parse backFields; drop title computation |
| `importing/PdfImporter.kt` | Drop title |
| `data/PassRepository.kt` | Drop `updateTitle`; `createManualPass` signature change; `localize()` drops title branch, translates backFields; `refreshPass` merge drops title/titleCustomized |
| `ui/CreatePassScreen.kt` | Remove title field |
| `ui/PassListScreen.kt` | Remove title `Text` from `PassCard` |
| `ui/PassDetailScreen.kt` | Remove title/edit/delete-in-app-bar/TitleEditDialog; add flip icon + 3D flip + back view |
| `ui/PassDetailViewModel.kt` | Remove `updateTitle` |
| `PassApp.kt` | `PendingPass` simplified |
| `MainActivity.kt` | Nav route simplified, `createManualPass` call site updated |
| Various existing test files | Remove title/updateTitle/computeTitle coverage; add backFields coverage (see Testing section) |
