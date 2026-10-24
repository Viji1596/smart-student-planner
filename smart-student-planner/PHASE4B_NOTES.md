# Phase 4b: fixes for missed sessions and reminders, and editing tasks

## What was wrong (found from your 3-minute task)
1. A session whose time was over, but that wasn't ticked off or counted as missed yet (there is a
   5 minute grace period), was hidden from Home. It was neither "upcoming" nor "earlier today", so
   the task looked gone, though it was still open in Tasks.
2. The "missed" check relied on a background job, which Android can postpone for a long time while
   the phone is idle. So the re-plan and the "Missed" notification came late or not at all.
3. The reminder before a session was skipped if the session started sooner than your "minutes
   before" setting (a task planned for 3 minutes from now got no reminder).
4. The plan only updated when an alarm, a data change or the hourly check happened, not when you
   simply opened the app.

## What changed
- Home now shows a session whose time is over as a card ("Time is up") with the check button and
  **Later**, instead of hiding it. After the 5 minute grace it becomes Missed and is planned again.
- Home (while the app is alive) and opening the app both check for missed sessions straight away,
  so the plan no longer depends on an alarm having arrived.
- The "was it missed?" alarm now does its work immediately in the alarm itself (not in a background
  job that can be postponed), so the notification arrives on time even when the phone is idle.
- If the "minutes before" moment has already passed but the session hasn't started, you now get the
  reminder **at the start** instead of nothing.
- The same task can trigger at most 2 "Missed" notifications a day. After that it is re-planned quietly.
- **Edit a task:** tap a task in Tasks (or use its menu -> Edit) to change the title, priority,
  duration and due date. Progress already made is kept. If the new duration is no more than what
  you already did, the task counts as finished.

## Tests
- 103 planning tests (new: a session that just ended stays visible, a missed 3-minute task is planned
  again, reminders at the start when the lead time has passed).
- 34 backend tests. No backend changes: no redeploy needed.

## Update steps
1. Copy the zip over your project. 2. File -> Sync Project with Gradle Files.
3. Run (phone connected) or Generate APKs + `adb install -r`. Your data is kept.

## How to check it (about 15 minutes)
1. Profile -> Reminders: **Remind me** must be ON (the test button works even when it is off).
2. Add a task of 3 minutes. Note the planned time on Home.
3. A reminder comes at that time (or a few minutes before it).
4. Don't tick it. When the time is up, Home shows it as "Time is up" with the check and Later buttons.
5. About 5 minutes after it ends: a "Missed: ..." notification, and a new session appears on Home
   (within 30 seconds if the app is open).
6. Tap a task in Tasks to edit it.

If a notification still doesn't come, Profile -> Reminders shows what's blocked. On Samsung phones set
the app's battery usage to Unrestricted.
