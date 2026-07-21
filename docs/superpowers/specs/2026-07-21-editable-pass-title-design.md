# Editable Pass Title — Design

**Date:** 2026-07-21
**Status:** Approved for planning

## Goal

Let the user set/override a pass's title: (1) rename any pass at any time from its detail
screen, and (2) be prompted to edit the title right after importing a PDF (whose
auto-detected filename title is often not ideal).

## Approach

Both requests share one primitive — changing a pass's title — built once and used in two
places. The PDF-import prompt is the same rename dialog, auto-opened after import.

## Architecture

### Shared primitive — rename a pass

- **Room** (`data/PassDao.kt`): `@Query("UPDATE passes SET title = :title WHERE id = :id") suspend fun updateTitle(id: String, title: String)`.
- **Repository** (`data/PassRepository.kt`): `suspend fun updateTitle(id: String, title: String)` on
  `Dispatchers.IO`. A blank/whitespace-only title is ignored (no-op) so a pass can't be left
  untitled.
- **ViewModel** (`ui/PassDetailViewModel.kt`): `fun updateTitle(newTitle: String)` — calls the
  repo, then reflects the change on screen by updating `_pass` (`_pass.value = _pass.value?.copy(title = newTitle.trim())`). Blank input is ignored.

### Detail screen — the edit surface (`ui/PassDetailScreen.kt`)

- An **edit (pencil) `IconButton`** in the `TopAppBar` actions, before the delete button.
- Tapping it opens an `AlertDialog` containing an `OutlinedTextField` pre-filled with the
  current title, plus **Save** (calls `viewModel.updateTitle`, dismisses) and **Cancel**
  (dismisses). Save is disabled when the field is blank.
- Dialog visibility is `rememberSaveable` local state.

### PDF-import prompt — reuse the dialog

After a successful import, the post-import navigation carries whether to auto-open the rename
dialog. Only PDF-sourced passes set it.

- **Signal type** (`PassApp.kt`): replace `pendingPassId: MutableStateFlow<String?>` with
  `pendingPass: MutableStateFlow<PendingPass?>`, where `data class PendingPass(val id: String, val editTitle: Boolean)`.
- **Set-sites** (`MainActivity.kt`): the picker success, the VIEW/SEND intent success, and the
  walletpasses success each set `pendingPass.value = PendingPass(pass.id, pass.sourceFormat == SourceFormat.PDF)`.
- **Navigation** (`MainActivity.kt` `AppNav`): the `LaunchedEffect` navigates to
  `detail/${p.id}?editTitle=${p.editTitle}` and clears the signal. The `detail` composable
  gains an optional `editTitle` nav argument (`NavType.BoolType`, default `false`), passed to
  `PassDetailScreen(openTitleEditor = ...)`. When `true`, the dialog opens on first
  composition (seeded into the `rememberSaveable` visible state).

## Data flow

**Rename (anytime):** tap pencil → dialog (pre-filled) → Save → `viewModel.updateTitle` →
`repo.updateTitle` (Room) → `_pass` updated → title re-renders. The list screen observes Room
via `observeAll()` and reflects the new title automatically.

**PDF import:** pick PDF → import (auto title from filename) → `PendingPass(id, editTitle=true)`
→ navigate to `detail/{id}?editTitle=true` → dialog auto-opens pre-filled → user edits/saves
or cancels (keeping the filename title).

## Error handling

- Blank title → ignored at both the ViewModel and repository layers (defense in depth); the
  Save button is also disabled when blank, so it can't normally be submitted.
- Renaming a non-existent id → the `UPDATE` affects 0 rows, a harmless no-op.

## Testing

- **`PassRepository.updateTitle`** (Robolectric, in-memory Room): insert a pass, `updateTitle`,
  `getById` → title changed; a blank title leaves the title unchanged.
- **`PassDetailViewModel.updateTitle`** (Robolectric): construct with a repo holding a pass,
  call `updateTitle("New")`, assert `pass.value?.title == "New"`; blank input leaves it
  unchanged.
- **UI + PDF auto-open** — device-verified: rename a pass from the detail pencil; import a PDF
  and confirm the rename dialog auto-opens pre-filled, and that Save/Cancel behave. (Build +
  install automated; the user confirms — no screenshots.)

## Files

| File | Change |
|------|--------|
| `data/PassDao.kt` | New `updateTitle` query |
| `data/PassRepository.kt` | New `updateTitle` (blank-safe) |
| `ui/PassDetailViewModel.kt` | New `updateTitle`; reflect change in `_pass` |
| `ui/PassDetailScreen.kt` | Edit pencil + rename `AlertDialog`; `openTitleEditor` param |
| `PassApp.kt` | `pendingPassId` → `pendingPass: MutableStateFlow<PendingPass?>` + `PendingPass` |
| `MainActivity.kt` | Set `PendingPass` at import sites; `editTitle` nav arg; navigate with flag |
| `test/.../data/PassRepositoryUpdateTest.kt` | New — `updateTitle` round-trip + blank-safe |
| `test/.../ui/PassDetailViewModelTest.kt` | New — `updateTitle` reflects in `pass` |

## Task breakdown (subagent-driven)

1. `updateTitle` in DAO + repo (+ blank-safe) with Room tests.
2. `PassDetailViewModel.updateTitle` with tests.
3. Detail-screen edit pencil + rename dialog (`openTitleEditor` param).
4. `PendingPass` signal + `editTitle` nav arg; auto-open dialog for PDF imports.
5. Device verification (manual rename + PDF-import auto-prompt).
