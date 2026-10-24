# Phase 4: reminders and automatic re-planning

## The idea
Until now the plan was recalculated live "from now", so its sessions slid forward as time passed.
That can't drive reminders or tell you "you missed it". So today now has a **committed plan**: a
snapshot that stays put until something real changes.

The plan is rebuilt (from the current time onward) when:
- you finish or skip a session, or a task, habit, class or setting changes,
- a session is missed,
- a new day starts,
- you tap **Re-plan** on Home.

Finished and missed sessions stay as history ("Earlier today"). A session under way is kept and its
minutes are never planned twice.

## What you'll see
Home
- Today's plan no longer slides: a session planned for 15:00 stays at 15:00.
- Each session has a round check button (done) and **Later** (skip it; its work is planned again
  for later today).
- **Re-plan** (next to "Today's plan") rebuilds the rest of today.
- **Earlier today** lists sessions you finished and ones you missed. On a missed one, **I did it**
  records the work after all.
- Breaks between sessions are shown ("10 min break") and are laid out from the committed plan.

Missed sessions
- About 5 minutes after a planned session should have ended without being ticked, it counts as missed.
- The app re-plans, and (if reminders are on) a notification tells you where the work went:
  "Missed: Assignment. Moved to 17:30 today." or "It didn't fit today, so it continues tomorrow."
- This happens even if the app is closed (an alarm wakes it just after each session's end),
  plus an hourly safety check.

Reminders (Profile -> Reminders)
- Notification before each study session (you choose how many minutes before; 0 = at the start).
- "Time to leave" for college (from your travel settings), optional meal and rest reminders,
  and a morning summary at your wake-up time ("3 sessions planned, 4 h of work").
- Reminder choices belong to this phone and are not synced.
- A **Send a test notification** button checks that notifications reach you.
- Android 13+ asks for notification permission when you switch Reminders on.
- "Allow exact timing" is optional: without it reminders can arrive a few minutes late.
- On Samsung and some other phones, set the app's battery usage to **Unrestricted**, otherwise the
  system may stop its alarms.

Smarter planning
- Finishing today's share of a big task no longer makes another share of the same task appear today.

## Tests
- 99 planning tests, including: a missed session is re-planned after "now", a session under way is
  not planned twice, skipping, re-planning twice gives the same plan, nothing overlaps classes,
  reminder times, and random scenarios.
- 34 backend tests (no backend changes in this phase).

## Update steps
No backend changes, so Render needs no redeploy and there is nothing to migrate on the server.
1. Copy this zip's contents over your project (replace when asked).
2. Android Studio: File -> Sync Project with Gradle Files.
3. Build and install over the old app (your data is kept; the phone database upgrades 4 -> 5):
   Run with the phone connected, or Build -> Generate App Bundles or APKs -> Generate APKs and
   `adb install -r app-debug.apk`.
4. Optional: `git add .`, `git commit`, `git push` to save it on GitHub.

## Quick checks (about 25 minutes, mostly waiting)
1. Profile -> Reminders -> switch on, allow notifications, tap **Send a test notification**.
   You should get one. If not, follow the on-screen hints.
2. Set "Minutes before" to 1.
3. Add a task: 15 minutes, due today. On Home, note its planned time and keep the app in the background.
   You should get a notification about a minute before it. Stay where you are.
4. Don't tick it. About 20 minutes after it starts you should get "Missed: ..." with the new time.
5. Open the app: it appears under "Earlier today" as Missed, with a new session later.
6. Add another task, then tap **Re-plan**: the future sessions move; earlier ones stay.

## If something goes wrong
- Crash on launch mentioning "Migration": uninstall the app, run again. Your data is on the server.
- No notifications: Profile -> Reminders shows what is blocked; use "Open notification settings".
- Notifications arrive late: allow exact timing, and set battery usage to Unrestricted.
- Plan looks stale: tap Re-plan.

## Known limits (planned for later)
- The committed plan is per phone. A second device makes its own plan and its own reminders.
- Tomorrow's sessions are planned live until tomorrow begins; its reminders are set just after midnight.
- You can't yet drag a session to another time (the chatbot in phase 6 will do "move this to evening").
