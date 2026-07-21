# Ideas / Backlog

Unscheduled feature ideas to investigate later. Not yet designed or committed to.

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
