# Pass Strip & Logo Rendering — Design

**Date:** 2026-07-21
**Status:** Approved for planning

## Goal

Render the `logo` and `strip` artwork embedded in `.pkpass` files so passes look
recognizable instead of a flat colored card. Motivating case: a Loopy Loyalty stamp
card (`stettbacher.pkpass`) whose visual identity lives entirely in its `strip@2x.png`.

## Scope

- **In:** render `logo` and `strip` images.
- **Out (deferred):** `thumbnail`, `background`, `footer` images; `icon` (OS-only, never
  shown in-pass). Still `.pkpass`-only.

## Approach

Images are **extracted on-demand from the already-stored raw `.pkpass`** at render time,
decoded on a background thread and cached in memory. This works retroactively for
already-imported passes (no re-import, no DB migration) and keeps a single source of
truth (the raw file we already persist).

## Architecture

**New unit — `PassImageLoader`** (`app/src/main/java/ch/bigli/passes/images/PassImageLoader.kt`):

```kotlin
enum class PassImage(val baseName: String) { LOGO("logo"), STRIP("strip") }

class PassImageLoader {
    /** Returns the decoded image, or null if absent/corrupt/unreadable. Never throws. */
    suspend fun load(rawFilePath: String, image: PassImage): Bitmap?
}
```

- Opens the `.pkpass` (a zip) at `rawFilePath` via `java.util.zip.ZipFile` and selects the
  best available resolution for the requested image: **`<base>@3x.png` → `<base>@2x.png`
  → `<base>.png`**. Decodes the chosen entry with `BitmapFactory.decodeStream`.
- Runs on `Dispatchers.IO`.
- In-memory `android.util.LruCache` keyed by `"$rawFilePath#${image.baseName}"`, so the zip
  is read/decoded at most once per (pass, image); re-opening a pass is instant. A cache hit
  returns the same `Bitmap` instance.
- Any failure (missing entry, unreadable zip, decode returns null) → returns `null`.

**App wiring:** one shared instance `PassApp.imageLoader` (like `repository`), passed down
through `MainActivity`'s `AppNav` into the screens, so the cache is process-wide.

**No change to the `Pass` domain model or Room schema** — rendering derives from
`pass.rawFilePath`.

## UI integration

**Detail screen (`PassDetailScreen`):**
- Load `LOGO` and `STRIP` asynchronously via `produceState(pass.rawFilePath)`; each starts
  null and updates when decoded.
- **Logo:** shown at the top in place of the organization-name text when present; falls back
  to the existing org text when absent.
- **Strip:** rendered full-width, edge-to-edge, directly under the header (Apple's placement),
  above the fields/barcode. `ContentScale.FillWidth`, natural aspect ratio from the bitmap.

**List card (`PassListScreen` / `PassCard`):**
- Show the `LOGO` small in the card header when present (keeps rows uniform). The strip is
  **not** shown in the list (avoids variable-height rows); it appears only on the detail
  screen.

**Contrast interaction:** the `legibleTextColor` logic (shipped in the barcode/legibility fix)
still governs the title/field text, which is drawn on the flat card background — never over
the strip. Strip and logo are standalone image blocks with no text overlaid, so there is no
conflict.

## Error handling

- Missing image → that block does not render (logo → text fallback; strip → omitted).
- Corrupt/undecodable image or unreadable zip → treated as absent (`null`); no crash.
- All loading is off the main thread; the UI shows the pass without art until (or unless) it
  loads.

## Testing

**`PassImageLoaderTest`** (Robolectric — `BitmapFactory` requires it). A new fixture
`app/src/test/resources/fixtures/withimages.pkpass` contains `pass.json` plus tiny valid PNGs
at distinct pixel sizes so resolution selection is assertable:
- `logo@2x.png` = 2×2, `logo@3x.png` = 3×3, `strip@2x.png` = 4×2.

Assertions:
- `load(LOGO)` returns non-null and selects **@3x** when both exist (bitmap width == 3).
- `load(STRIP)` returns the @2x when it is the only strip present (bitmap width == 4).
- A pkpass with no images (reuse `sample.pkpass`) → `load(...)` returns `null`.
- A garbage/nonexistent `rawFilePath` → `null` (no throw).
- Cache: a second `load` of the same (path, image) returns the same `Bitmap` instance.

**UI:** no Compose unit test; verified on-device with the real Stettbacher pass (its
`logo@2x` + `strip@2x` should appear on the detail screen, logo on the list card).

## Files

| File | Change |
|------|--------|
| `images/PassImageLoader.kt` | New — enum, loader, LruCache |
| `test/.../images/PassImageLoaderTest.kt` | New — loader tests |
| `test/resources/fixtures/withimages.pkpass` | New — fixture with tiny PNGs |
| `PassApp.kt` | Add `val imageLoader = PassImageLoader()` |
| `MainActivity.kt` | Pass `imageLoader` into `AppNav` → screens |
| `ui/PassDetailScreen.kt` | Logo (top, text fallback) + full-width strip via `produceState` |
| `ui/PassListScreen.kt` | Small logo in card header when present |

## Task breakdown (subagent-driven)

1. `PassImageLoader` + `withimages.pkpass` fixture + tests (TDD).
2. Wire loader through `PassApp` → `MainActivity`/`AppNav`; render logo + strip on the detail screen.
3. Render logo in the list card.
4. On-device verification with the Stettbacher pass.
