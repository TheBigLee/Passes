# pkpass Localization — Design

## Goal

Apple's pkpass format supports per-language localization: a pass can bundle `<lang>.lproj/`
folders containing a `pass.strings` translation table (and optionally localized images). Passes
imported into this app currently ignore all of that — every field label/value, `organizationName`,
`description`, and image is always read from the pass's top-level (default-language) content.

This feature makes localizable pkpass content (field text, organization name, description-derived
title, and images) render in whichever language the pass supports that best matches the device's
*current* language — live, re-evaluated on every read, so a device language change takes effect
immediately (well: on the next screen/app load — see "Live means read-time, not reactive" below),
with no re-import needed.

## Scope

**In scope:** field labels/values, `organizationName`/`subtitle`, the auto-generated `title`
(unless the user has manually renamed the pass), and logo/strip images.

**Out of scope (tracked separately in `docs/ideas.md`):**
- Per-app language support (Android's own UI-language picker) — requires extracting this app's
  own hardcoded UI strings into `strings.xml` first, which doesn't exist today. Unrelated to
  pkpass content and a much larger, separate project.
- Non-pkpass sources (PDF, manual entry, wallet-link import) have no localization data and are
  unaffected.

## Locale matching

- Language-only match: `Locale.getDefault().language` (e.g. device `de-CH` → language code `de`)
  is compared case-insensitively against `<code>.lproj/` folder names found in the pkpass zip.
  No region-specific fallback (e.g. `de-CH.lproj` is not specially preferred over `de.lproj`) —
  real-world pkpass files essentially never ship region-specific folders, so this stays simple.
- No matching folder (device language not supported by the pass, or the pass has no `.lproj`
  folders at all) → no translation is applied; all content renders exactly as it does today.
  This is the common case for the vast majority of already-imported, single-language passes and
  must be a complete no-op for them.
- A matching folder exists but a specific string/image has no override → falls back to the
  top-level (default) value for *that item only* (not all-or-nothing for the whole pass).

## Live means read-time, not reactive

"Live" here means: every time a `Pass` is loaded from the repository (`observeAll()`/`getById()`),
localization is freshly recomputed using whatever `Locale.getDefault()` currently returns. It does
NOT mean the UI reactively updates mid-screen the instant you change the system language in
Settings while the app sits in the foreground — Android normally recreates the activity (and this
app's ViewModels/ repository reads along with it) when the system locale changes, which is what
actually surfaces the new language. No extra reactive plumbing is being added to detect
in-place locale changes without an activity recreation.

## Architecture

### `PkPassLocalization` (new, `importing/PkPassLocalization.kt`)

A small stateless helper, given a pkpass zip's raw bytes:

- Scans zip entry names for `<code>.lproj/` folders (i.e. names matching `^([^/]+)\.lproj/`).
- Picks the folder whose `<code>` matches `Locale.getDefault().language` (case-insensitive).
- If found, parses that folder's `pass.strings` file: Apple's simple
  `"KEY" = "VALUE";` format, one entry per line, supporting `//` and `/* */` comments and
  `\"`/`\\` escapes within quoted strings, and decoding as UTF-16 (with BOM) if a BOM is present,
  otherwise UTF-8.
- Exposes `translate(text: String?): String?` — looks up `text` verbatim in the parsed table,
  returning the translation if present, otherwise `text` unchanged (so callers never need a
  separate "no localization available" branch — translating with no match is always safe and
  correct).
- Exposes `imageEntryName(baseName: String, names: Set<String>): String?` — same
  `@3x` → `@2x` → base preference logic `PassImageLoader` already uses, but scoped to files inside
  the matched `.lproj` folder (e.g. prefers `de.lproj/logo@2x.png`); returns `null` if the folder
  has no override for that image so the caller can fall back to the existing top-level lookup.

### `PkPassImporter` — unchanged

Deliberately untouched. It keeps parsing and storing the raw, untranslated top-level `pass.json`
content exactly as it does today (this raw text is also exactly the key set `pass.strings` files
use, so nothing needs to change here for translation to work at read time). The only addition is
that it now also stores the raw `description` string on `Pass` (see Data model), and computes
`title` via the new shared `computeTitle(...)` function instead of its own inline logic (same
behavior, just extracted so `PassRepository` can call the identical logic later).

### `PassRepository` — new live-translation decorator

A private function, applied inside `observeAll()`'s `.map` and at the end of `getById()`, for any
`Pass` where `sourceFormat == SourceFormat.PKPASS`:

1. Read `File(pass.rawFilePath)` bytes (swallow errors defensively — file missing/corrupt just
   means the pass renders untranslated, matching current behavior; never crashes the read path).
2. Build a `PkPassLocalization` instance from those bytes.
3. Translate `organization`, `subtitle`, `description`, and each field's `label`/`value`.
4. If `!pass.titleCustomized`: recompute `title` via `computeTitle(translatedFields,
   translatedDescription, translatedOrganization)` (the same function `PkPassImporter` uses at
   import time, now fed live-translated inputs). If `titleCustomized`, leave `title` untouched.
5. Return the decorated `Pass` (in-memory only — none of this is written back to the database;
   the stored row always keeps the original untranslated snapshot).

Non-pkpass passes pass through unchanged.

### `computeTitle` (new shared function, domain layer)

Extracted from `PkPassImporter`'s existing inline logic, unchanged behavior:

```kotlin
fun computeTitle(fields: List<PassField>, description: String?, organizationName: String?): String {
    val primary = fields.filter { it.position == FieldPosition.PRIMARY }
    return when {
        primary.size >= 2 -> "${primary[0].label} → ${primary[1].label}"
        primary.isNotEmpty() -> primary[0].value
        else -> description ?: organizationName ?: "Pass"
    }
}
```

(Exact primary-field selection logic — label vs value preference — is preserved from the current
implementation; see the plan for the literal diff.)

### `PassImageLoader` — locale-aware image resolution

`load(rawFilePath, image)` gains a live check: after opening the zip, use
`PkPassLocalization` to find the current-locale folder and ask for its override of that image;
if present, decode that entry; otherwise fall back to the existing top-level lookup (unchanged).
The in-memory cache key gains `Locale.getDefault().language`, so switching languages mid-process
(activity recreation keeps the same `PassImageLoader` singleton in `PassApp`) can't serve a
stale bitmap for the wrong language.

## Data model changes

Two new columns on `Pass`/`PassEntity`, both needed to support live title recomputation:

- `description: String? = null` — raw `pass.json` description, previously read transiently at
  import time and discarded. Needed so the title's fallback chain can be fully reproduced at
  read time from translated inputs.
- `titleCustomized: Boolean = false` — set to `true` by `PassRepository.updateTitle()` (the
  pencil-icon rename) whenever a user manually renames a pass. Once set, it never reverts (no
  "reset to auto-title" affordance — not requested, out of scope). Prevents the live-translation
  decorator from overwriting a deliberately custom title when the device language changes.

One migration, `MIGRATION_3_4`, adds both columns (`PassDatabase` bumps to version 4). Existing
rows get `description = NULL`, `titleCustomized = 0` (false) — meaning every pre-existing pass is
treated as "not customized," so its title *will* start being live-recomputed after this update
ships (a behavior change, but the correct one: it makes existing passes benefit from localization
too, and for a never-renamed pass the recomputed title in the current locale is what the user
would want).

`refreshPass` (the existing pkpass auto-update from Phase 4) is otherwise unaffected: it keeps
overwriting the pkpass zip file, and the merged `Pass` written to the DB preserves
`titleCustomized` from the previously-stored pass, same pattern as the existing `id`/`title`
preservation.

## Testing

- `PkPassLocalization`: folder detection (exact match, case-insensitivity, no match, multiple
  folders), `.strings` parsing (comments, escapes, UTF-8, UTF-16 with BOM), `translate` fallback
  behavior, `imageEntryName` resolution/precedence.
- `PkPassImporter`: `description` is stored; `computeTitle` produces the same title as before for
  all three branches (2+ primary fields / 1 primary field / neither, using description then
  organization then `"Pass"`).
- `PassRepository`: import a synthetic multi-`.lproj` pkpass; assert `getById`/`observeAll`
  render translated fields/organization/title when `Locale.getDefault()` is set (via
  `Locale.setDefault(...)` in the test) to a matching language, and render the untranslated
  top-level content when set to a non-matching one — proving the same stored row renders
  differently depending on current locale. A separate test proves a manually-renamed
  (`titleCustomized = true`) pass's title does NOT change across a locale switch.
- `PassImageLoader`: a pkpass with a localized image override renders that image when the current
  locale matches, and falls back to the top-level image otherwise; cache doesn't leak a bitmap
  across a locale switch.
- `PassDatabaseMigration3Test` (new): hand-built v3 schema → `MIGRATION_3_4` → verify
  `description`/`titleCustomized` columns exist with the documented defaults, no data loss,
  following the same pattern as the two existing migration tests.

## Files touched

| File | Change |
|---|---|
| `importing/PkPassLocalization.kt` | New: folder matching, `.strings` parsing, `translate`/`imageEntryName` |
| `importing/PkPassLocalizationTest.kt` | New |
| `domain/Pass.kt` | Add `description`, `titleCustomized` fields; add `computeTitle` function |
| `data/PassEntity.kt` | Mirror new fields + mapping |
| `data/PassDatabase.kt` | `MIGRATION_3_4`, version bump to 4 |
| `data/PassDatabaseMigration3Test.kt` | New |
| `importing/PkPassImporter.kt` | Store `description`; use shared `computeTitle` |
| `data/PassRepository.kt` | Live-translation decorator in `observeAll()`/`getById()`; `updateTitle` sets `titleCustomized` |
| `data/PassRepositoryLocalizationTest.kt` | New |
| `images/PassImageLoader.kt` | Locale-aware image resolution + cache key |
| `images/PassImageLoaderTest.kt` | Extend existing file with localized-image case |
