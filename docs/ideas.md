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
