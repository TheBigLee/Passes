# pkpass Auto-Update — Design

**Date:** 2026-07-21
**Status:** Approved for planning
**Phase:** 4 (pkpass auto-update; Apple signature/manifest verification is explicitly deferred to a later phase).

## Goal

Keep imported `.pkpass` passes fresh after the issuer updates them server-side (gate/seat
changes, balance updates, voided tickets), without needing a real Apple Push Notification
registration (which this app cannot obtain, since it isn't a real Apple Wallet client). Support
both a periodic background check and an explicit user-triggered refresh.

## Scope

- **In:** polling a pass's `webServiceURL` (parsed from `pass.json`, already stored as
  `Pass.updateInfo`) for a fresher pkpass; re-importing on change; a `voided` state for 410
  responses; a background `WorkManager` job every 6 hours; pull-to-refresh on the pass detail
  screen.
- **Out (deferred):** Apple signature/manifest (`manifest.json` + `signature`) verification;
  real APNs device registration; Google Wallet JSON update support (no update protocol parsed
  from Google Wallet imports today).

## Why polling, not push

Apple's real PassKit web service protocol is push-triggered: the device registers
(`POST /v1/devices/{id}/registrations/{passTypeId}/{serial}`), Apple's servers send an APNs
push when the issuer updates the pass, and the client then fetches the fresh pass. This app has
no APNs registration and isn't a signed Apple Wallet client, so there's no push channel
available. Instead, the app **polls** the same fetch endpoint
(`GET /v1/passes/{passTypeId}/{serial}`) on a fixed interval, using the same
`Authorization: ApplePass {authToken}` header and `If-Modified-Since` caching that the real
protocol uses for the fetch step — just without the "wait for a push" trigger step.

## Data model changes

`Pass` (and `PassEntity`) gain two fields:

```kotlin
data class Pass(
    // ...existing fields...
    val voided: Boolean = false,
    val lastModified: String? = null, // raw HTTP Last-Modified header from the last successful fetch
)
```

**Room migration:** bump `PassDatabase` from `version = 1` to `version = 2` with a real
`Migration(1, 2)` (not `fallbackToDestructiveMigration`) — this app has live user data on a
real device, so destructive migration would silently delete every imported pass on update:

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE passes ADD COLUMN voided INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE passes ADD COLUMN lastModified TEXT")
    }
}
```

`Room.databaseBuilder(...).addMigrations(MIGRATION_1_2).build()` in `PassApp`.

## Update protocol: `PassRepository.refreshPass`

```kotlin
sealed interface RefreshResult {
    data class Updated(val pass: Pass) : RefreshResult
    data object Unchanged : RefreshResult
    data object Voided : RefreshResult
    data object NotUpdatable : RefreshResult // no updateInfo, not a pkpass, or already voided
    data class Error(val message: String) : RefreshResult
}

suspend fun refreshPass(id: String): RefreshResult
```

Behavior:
1. Load the stored `Pass` by `id`. If `sourceFormat != PKPASS`, `updateInfo == null`, or
   `voided == true` → `NotUpdatable` (no network call).
2. `GET {updateInfo.webServiceUrl}/v1/passes/{updateInfo.passTypeId}/{updateInfo.serialNumber}`
   via `HttpURLConnection` (same pattern as `importFromUrl`), with headers:
   - `Authorization: ApplePass {updateInfo.authToken}`
   - `If-Modified-Since: {pass.lastModified}` (omitted if null)
3. Response handling:
   - **200** — read the body bytes, overwrite the existing file at `pass.rawFilePath`, run
     `PkPassImporter.import(bytes, pass.rawFilePath, pass.title)` to get a freshly-parsed
     `Pass`, then persist a merged pass: all fields from the fresh import **except**
     `id` (kept as the original) and `title` (kept as the original, since the user may have
     renamed it — title is never overwritten by an update), `voided = false`,
     `lastModified` = the response's `Last-Modified` header (if present, else unchanged).
     Returns `Updated(mergedPass)`.
   - **304** — no changes. Returns `Unchanged`.
   - **410** — persist `voided = true` on the existing pass (stop future polling; pass stays
     visible). Returns `Voided`.
   - Any other HTTP status, or an `IOException` — returns `Error(message)`; the stored pass is
     untouched, so it will be retried on the next cycle.
4. All network I/O runs on `Dispatchers.IO` (same convention as `importFromUrl`).

## Background scheduling

New dependency: `androidx.work:work-runtime-ktx` (WorkManager), added to the version catalog
at its current latest stable version.

- **`update/PassUpdateWorker.kt`** — a `CoroutineWorker` that queries all passes with
  `sourceFormat == PKPASS && updateInfo != null && !voided` and calls `refreshPass(id)` for
  each, sequentially (pass counts are small; no need for concurrency). Returns `Result.success()`
  regardless of individual per-pass errors (`Error` results are non-fatal and just retried next
  cycle by nature of the periodic schedule, not by `WorkManager` retry).
- **Scheduling** — in `PassApp.onCreate()`, once, after the database is built:
  ```kotlin
  val request = PeriodicWorkRequestBuilder<PassUpdateWorker>(6, TimeUnit.HOURS)
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .build()
  WorkManager.getInstance(this).enqueueUniquePeriodicWork(
      "pass-update-check", ExistingPeriodicWorkPolicy.KEEP, request,
  )
  ```
- The worker needs a `PassRepository` instance; since `PassApp` already builds the singleton
  `PassRepository` at startup, the worker looks it up via `(applicationContext as PassApp)`
  the same way `MainActivity` does today (no new DI mechanism introduced).

## UI changes

- **Pass detail screen** (`PassDetailScreen.kt`): wrap the existing content in a Material3
  `PullToRefreshBox` (already available via the current compose-bom, no version bump needed).
  Pulling down calls `refreshPass(pass.id)` via the view model; on `Updated`, the screen
  reflects the new fields (it already observes the pass via `Flow`/`observeAll`, so this is
  automatic once the DB row changes); on `Unchanged`/`Error`/`NotUpdatable`, show a brief
  snackbar ("Up to date" / "Couldn't refresh"); on `Voided`, no separate snackbar — the voided
  banner (below) appears immediately since the DB row changed.
- **Voided state**: when `pass.voided`, the detail screen shows a small banner (e.g.
  "This pass has been voided by the issuer") near the top, and the barcode/strip render as
  today but visually de-emphasized is NOT required — keep it simple: banner only, no dimming,
  to avoid scope creep on the rendering path. The list screen shows a small "Voided" label
  next to the title of a voided pass's row.

## Error handling

- Network failure, timeout, unexpected status code during a refresh → `Error`, pass state
  untouched, retried next cycle (background) or left for the user to pull again (manual).
- A pass with no `updateInfo` (manual/scanned/PDF/Google-JSON passes) is simply never selected
  by the worker and pull-to-refresh's button state can stay as-is (still shown; a refresh on
  such a pass just returns `NotUpdatable` → snackbar "This pass can't be refreshed").
- Malformed pkpass in a 200 response (fails `PkPassImporter.import`) → treated as `Error`
  (catch the importer's `ImportError`), original stored pass is left untouched — never leave
  the user with a half-updated or corrupt pass.

## Testing

- **`PassRepository.refreshPass`** (Robolectric, in-memory Room, the existing `TestHttpServer`
  from `PassRepositoryUrlTest` extended to serve conditional/header-aware responses): covers
  200-with-fresh-pkpass (title preserved, other fields updated, `lastModified` stored),
  304-unchanged (no field changes), 410-voided (`voided` flips, subsequent `refreshPass` call
  returns `NotUpdatable` without a network request), and a malformed-200-body case
  (`Error`, original pass untouched).
- **Room migration**: a `MigrationTestHelper`-based test (Room's standard migration test
  pattern) verifying `MIGRATION_1_2` runs cleanly against a v1 database and the new columns
  have the expected defaults.
- **`PassUpdateWorker`**: not unit-tested directly (thin iteration wrapper over `refreshPass`,
  which is already covered); verified via the same on-device pass as the manual refresh, plus
  a code-level check that it's wired with the correct constraints/interval.
- **UI** (pull-to-refresh gesture, voided banner, list badge): device-verified, no screenshots.

## Files (indicative — finalized in the implementation plan)

| File | Change |
|------|--------|
| `domain/Pass.kt` | Add `voided`, `lastModified` fields |
| `data/PassEntity.kt` | Add matching columns + `toEntity`/`toDomain` mapping |
| `data/PassDatabase.kt` | Bump to version 2, add `MIGRATION_1_2` |
| `data/PassDao.kt` | Add update queries (voided flag, lastModified, full-row update) |
| `data/PassRepository.kt` | Add `refreshPass(id): RefreshResult` |
| `update/PassUpdateWorker.kt` | New — periodic background refresh |
| `PassApp.kt` | Build DB with migration; schedule the periodic work request |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | Add WorkManager dependency |
| `ui/PassDetailScreen.kt` | `PullToRefreshBox`, voided banner, snackbar on refresh result |
| `ui/PassListScreen.kt` | Voided badge on list rows |
| `test/.../data/PassRepositoryRefreshTest.kt` | New — refresh protocol coverage |
| `test/.../data/PassDatabaseMigrationTest.kt` | New — migration coverage |
