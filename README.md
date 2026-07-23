# Passes

<img src="docs/assets/logo.png" alt="Passes app logo" width="96" height="96">

Native Android pass wallet. Store, view, and auto-update passes — no Google/Apple account needed.

> [!WARNING]
> This app has been written by Claude code without human verification or review (so far).
> Use it at your own risk!!!

## Features

- **Import `.pkpass` files** (Apple Wallet format) — parses `pass.json`, images, barcode, fields.
- **Import PDF tickets** — extracts a barcode/QR from a PDF page.
- **Manual entry** — type in or scan a barcode/QR directly.
- **Auto-update** — passes with a `webServiceURL` poll for changes every 6h (WorkManager) and support pull-to-refresh on the detail screen. Handles voided passes (410) and static `voided`/`expirationDate` fields. Can be disabled per-pass (on by default) via a switch on the back of the pass.
- **Flip-to-back detail view** — a persistent info icon flips the pass to reveal pkpass `backFields` (with HTML/link rendering) and Delete.
- **Type-aware field layout** — boarding passes get a Wallet-style layout (big origin/destination row with a plane icon, header/auxiliary/secondary fields in their own rows); every other pass type stacks its primary field(s) large, then flows secondary/auxiliary fields below with nothing capped or dropped.
- **Brightness-boost** while showing a barcode for scanning.

## Project layout

```
app/src/main/java/ch/bigli/passes/
  domain/     Pass model (format-agnostic)
  importing/  pkpass / PDF / manual-entry importers
  data/       Room database, PassRepository (incl. refresh/update polling)
  update/     WorkManager periodic update worker
  images/     logo/strip image loading
  barcode/    barcode/QR rendering
  ui/         Compose screens (list, detail, import)
tools/generate_test_passes.py   generates sample .pkpass files into sample-passes/
docs/
  ideas.md                       unscheduled backlog ideas
  superpowers/specs/, plans/      design docs + implementation plans per feature
```

## Build & run

Requires JDK 17+ (daemon toolchain resolves this automatically — run `./gradlew` bare, no `JAVA_HOME` override needed).

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Install on a device/emulator:

```bash
./gradlew :app:installDebug
```

## CI/Release

- `.github/workflows/ci.yml` — build + unit tests on push/PR.
- `.github/workflows/release.yml` — signed release build (keystore via `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` env vars).

## Contributing / workflow

New features go through a brainstorm → design doc → implementation plan cycle (see `docs/superpowers/specs/` and `docs/superpowers/plans/` for examples). Room schema changes must ship a real `Migration`, not `fallbackToDestructiveMigration()` — this app has real user data.
