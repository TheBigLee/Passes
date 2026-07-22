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
form only asks for title + barcode value + format. Instead, offer a proper structured form to
fill in the missing context Apple Wallet passes normally carry — e.g. event location, date, and
time for an event-style pass, or departure date/time for a boarding-pass-style one.

Points to investigate when picked up:
- **Trigger:** ask the user to pick a pass "kind" (event, boarding, loyalty, generic) up front,
  then show kind-specific fields, vs. one generic set of optional fields for all manual passes.
- **Data model:** these manually-entered fields would need to map onto existing `Pass` fields
  (`relevantDate`, `PassField`s with `FieldPosition`) so they render the same as imported passes
  and can feed the reminder-notification idea above.
- **Scope creep risk:** keep this a lightweight form, not a full pass-authoring tool.

## Multilingual pkpass support

Apple's pkpass format supports localization: a pass can bundle per-language `.lproj` folders
(e.g. `en.lproj/`, `de.lproj/pass.strings`) with translated field labels/values and even
localized images, selected by the device's locale. `PkPassImporter` currently only ever reads
the top-level `pass.json` and top-level images, ignoring any `.lproj` folders entirely — so a
multilingual pass always renders in whatever language its top-level fields happen to be in.

Points to investigate when picked up:
- **Detection:** scan the zip for `*.lproj/` entries during import.
- **Selection:** match against the device's current locale (with a fallback chain, e.g.
  `de-CH` → `de` → `Base`/top-level), similar to how Apple Wallet picks a language.
- **What's localizable:** Apple's `.strings` files translate field label/value text; images
  (`logo.png`, `strip.png`, etc.) can also have per-`.lproj` overrides.
- **Scope:** decide whether to support live language switching in-app or just pick once at
  import time based on the device locale at that moment.

## Support pkpass "back fields"

Apple's pkpass format lets a pass declare `backFields` (in addition to header/primary/
secondary/auxiliary fields) — extra key/value info meant to be shown on the "back" of the pass,
revealed by tapping/flipping it. `PkPassImporter`/`Pass` currently don't parse or store
`backFields` at all, so this information is silently dropped on import.

Points to investigate when picked up:
- **Data model:** parse `backFields` in `PkPassJson`/`PkPassImporter` similarly to the existing
  field positions, and add a place to store them on `Pass` (a new `backFields: List<PassField>`,
  reusing `FieldPosition` or a dedicated marker).
- **UI:** add a flip/reveal affordance on `PassDetailScreen` (e.g. a button or tap-to-flip
  animation) that shows the back-field content, then flips back.
- **Scope:** manually-entered/PDF passes have no natural source for back fields — this is
  pkpass-only for now.

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
