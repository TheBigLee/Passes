# Ideas / Backlog

Unscheduled feature ideas to investigate later. Not yet designed or committed to.

## Stronger CI checks: full lintRelease + static analyzer

CI currently runs `lintVitalRelease` (fatal-only lint) and `assembleRelease` on every PR (added
2026-07-22 after a release-only manifest-merge lint error slipped through to a merged PR). Two
further checks were considered and deliberately deferred:

- **Full `lintRelease`** (not just Vital) as a non-blocking/reporting step — surfaces real
  warnings (deprecated APIs, resource issues, etc.), not just fatal errors. Needs a lint baseline
  file first, since there's likely a backlog of pre-existing warnings that would otherwise show
  as noise on every unrelated PR.
- **A static analyzer (ktlint or detekt)** — not configured at all today. Needs a plugin
  addition plus an initial cleanup pass to establish a clean baseline before it can gate PRs.

## Reminder notification to open the pass before the event/flight

Notify the user to open/show the pass in the app ahead of the relevant time (e.g. before a
flight or event). Possible forms:

- A **scheduled notification** some configurable lead time before the event that, when tapped,
  opens that pass's detail screen.
- Alternatively a **sticky / ongoing (persistent) notification** when the event is near — similar
  to how Google/Apple Wallet surface an upcoming boarding pass on the lock screen.

Points to investigate when picked up:
- **Time source:** pkpass has `relevantDate` (already parsed into `Pass.relevantDate`); PDF/manual
  passes usually don't — may need a way to set/edit the date (ties into the editable-pass work).
- **Scheduling:** `AlarmManager` (exact alarms) vs `WorkManager`; behaviour after reboot.
- **Permissions:** `POST_NOTIFICATIONS` (Android 13+) and exact-alarm permission.
- **Deep link:** tapping the notification should open the specific pass (`detail/{id}`).
- **Lead time:** fixed vs user-configurable; sensible default (e.g. a few hours before).

## Structured form when adding a plain barcode/QR code

When creating a pass via manual entry or camera scan (the `MANUAL` create flow), the current
form only asks for barcode value + format — since the title field was removed entirely (it was a
synthesized value, not an actual Apple pkpass field), manually-created and PDF-imported passes now
carry no identifying text at all. Instead, offer a proper structured form to fill in the missing
context Apple Wallet passes normally carry — e.g. event location, date, and time for an
event-style pass, or departure date/time for a boarding-pass-style one.

Points to investigate when picked up:
- **Trigger:** ask the user to pick a pass "kind" (event, boarding, loyalty, generic) up front,
  then show kind-specific fields, vs. one generic set of optional fields for all manual passes.
- **Data model:** these manually-entered fields would need to map onto existing `Pass` fields
  (`relevantDate`, `PassField`s with `FieldPosition`) so they render the same as imported passes
  and can feed the reminder-notification idea above.
- **Scope creep risk:** keep this a lightweight form, not a full pass-authoring tool.

## Fullscreen the barcode/QR code

Tapping the barcode on `PassDetailScreen` should open it fullscreen (max brightness, max size)
for easier scanning, instead of the current fixed 240dp inline size.

Points to investigate when picked up:
- **Trigger:** tap-to-expand on the existing barcode `Image`, dismiss via tap/back.
- **Content:** decide whether the voided/expired notice (currently shown directly under the
  barcode on the regular screen) should also appear in the fullscreen view — a scanner staring
  at a fullscreen barcode arguably still needs to see it's void/expired.
- **Brightness:** the screen is already boosted to full brightness while `PassDetailScreen` is
  visible (see the `DisposableEffect` there), so fullscreen mode doesn't need its own brightness
  handling, just bigger scale.

## Show primary field values on boarding-pass-style passes

`PassDetailScreen`/`PassListScreen` only ever use pkpass `primaryFields` to build the auto-generated
title (the field *labels*, joined by an arrow — e.g. "Departure → Arrival"); the primary fields'
actual *values* (e.g. the departure/arrival airport codes themselves) are never shown anywhere in
the UI. Confirmed while device-testing pkpass localization (2026-07-22) on a real boarding pass
whose primary fields have explicit labels ("departsHeading"/"destinationHeading", translated via
`pass.strings` to "Departure"/"Arrival") — the title correctly showed "Departure → Arrival" but the
actual airport codes (ZRH/WAW) never appeared anywhere on the pass.

Points to investigate when picked up:
- **Display:** boarding-pass-style passes conventionally show the two primary values large and
  prominent (e.g. big airport codes with an arrow/plane icon between them) — worth a
  `PassType.BOARDING`-specific layout rather than bolting onto the generic fields grid.
- **Scope:** decide whether this only applies to `PassType.BOARDING` or should show primary field
  values generically for any pass type that has them.

## Non-primary fields are silently truncated past 4

`PassDetailScreen`/`PassListScreen` both do
`p.fields.filter { it.position != FieldPosition.PRIMARY }.take(4)` — if a pass's combined
header+secondary+auxiliary fields exceed 4, the rest are silently dropped with no indication.
Confirmed while device-testing pkpass localization (2026-07-22): a real boarding pass's auxiliary
fields (flight number, date, boarding time, class, status) were partly/fully cut off because
earlier header/secondary fields already filled the 4-slot cap.

Points to investigate when picked up:
- **Display:** either raise/remove the cap with a wrapping/scrollable layout, or make truncation
  visible (e.g. an overflow indicator) instead of silent data loss.
- **Priority:** decide whether header/secondary/auxiliary should be weighted differently when
  something has to be cut (e.g. always keep auxiliary fields like flight/date/boarding time, which
  tend to be more critical than secondary ones).
