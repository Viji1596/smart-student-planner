# Phase 4c: reminders before the end and at the end of a session

## What's new (Profile -> Reminders -> Study sessions)
Three separate reminders for every study session, each with its own switch:
1. **Before it starts**: with "Minutes before start" (default 5; 0 = at the start).
2. **Before it ends**: with "Minutes before end" (default 5; 0 = off).
3. **When it ends**: "Time's up: <task>". This notification has **Done** and **Later** buttons, so you can
   answer without opening the app:
   - Done = the same as ticking the session off (the work is recorded, the plan updates);
   - Later = skip it, and the work is planned again for later today.

"Other reminders" (time to leave, meals and rest, morning summary) are unchanged. They also use the
"Minutes before start" value.

## How it fits together (a 60-minute session at 14:00)
- 13:55  Starting soon: <task>
- 14:55  <task> ends in 5 min
- 15:00  Time's up: <task>  [Done] [Later]
- 15:05  if you did nothing: counted as missed, planned again, "Missed: ..." notification

## Details
- A session too short for the "before it ends" moment (e.g. a 3-minute task with a 5-minute heads-up) skips that one:
  it would fall before the session even starts. You still get the start and "Time's up" reminders.
- A session already under way still gets its "ends in" and "Time's up" reminders.
- The 5 minute grace period before a session counts as missed is unchanged.
- Reminder choices are stored on this phone only.

## Tests
- 111 planning tests (new: before-the-end timing, flexible minutes, zero = off, tiny tasks, each reminder
  can be turned off separately, the Time's-up reminder carries the session id for the buttons).
- 34 backend tests (no backend changes: no redeploy).

## Update steps
1. Copy the zip over your project. 2. File -> Sync Project with Gradle Files.
3. Run (phone connected). Then unplug the phone before testing reminders.

## How to check it (about 15 minutes)
1. Profile -> Reminders: "Remind me" ON; the three study-session switches ON; 2 minutes before start,
   2 minutes before end (short values make testing quick).
2. Add a task of 10 minutes. Note its planned time on Home and put the app in the background.
3. You should get: "Starting soon" 2 min before, "ends in 2 min" at 8 minutes in, then "Time's up"
   with Done and Later at the end.
4. Tap **Done** on the Time's up notification: the notification closes and the task shows progress
   (or finishes). Home updates when you open it.
5. Try again and tap **Later**: the work reappears later on Home.

If a notification doesn't come, Profile -> Reminders shows what is blocked. On Samsung phones set the
app's battery usage to Unrestricted.
