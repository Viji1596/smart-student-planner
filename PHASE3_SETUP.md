# Phase 3: habits (recurring tasks), "plan my day" scheduler, task progress

## What's new
Planner (the part you'll feel)
- **Habits tab**: things you repeat.
  - Every day (e.g. "Coding practice")
  - Fixed days (e.g. Mon/Wed/Fri)
  - N times a week (e.g. "Aptitude practice", 2 per week). The planner spreads the days out
    (2 per week = Monday and Thursday), and catches up if you miss one.
- **Home tab** now builds a real plan from your free time:
  - Pick Today or any of the next 6 days.
  - Today's plan starts from "now" and refreshes as the day goes on.
  - Sessions sit in your free slots, with 10 minute breaks, between classes and meals.
  - Tick a session when you finish it. A habit is marked done for today; a task gets that many
    minutes of progress (and completes when all its minutes are done).
  - A card lists anything that didn't fit and why ("not enough free time", "daily work limit").
    It turns red if something due within two days is at risk.
- **Tasks** keep progress ("20/60 min done").

How the planner decides (so it never feels random)
1. Urgency: overdue, due today, due within 2 days, within a week, later, no deadline.
2. Within the same urgency: High priority first, then the earlier deadline, then shorter tasks.
3. Habits count as "due today" work. Weekly habits follow the even-spread schedule.
4. Big tasks are spread over the days left (not in slivers under 30 minutes); a task you can
   finish in one go (90 min or less) is done in one go.
5. Long tasks are split into sessions of at most 90 minutes.
6. Non-urgent work is capped at 8 hours a day so the plan never fills every free minute.

Technical
- Backend: `Habit` and `HabitCompletion` tables, `/api/habits` and `/api/habit-completions`
  (synced like tasks), `spentMinutes` on tasks. 29 backend tests.
- App: all planning logic is in `domain/` (plain Kotlin). 40 unit tests, including 300 random
  scenarios that check sessions never overlap, stay in free time, and never lose minutes.
- Database upgrade 2 -> 3 keeps all existing data.

## Update steps
1. Copy this folder's files over your project (replace when asked).
   Your `backend/.env` and `android/local.properties` are not in the zip.
2. Backend (VPN off):
   ```powershell
   cd backend
   npx prisma migrate deploy --config prisma7.config.ts
   npx prisma generate --config prisma7.config.ts
   npm test
   npm run dev
   ```
3. Phone (USB connected): `adb reverse tcp:5000 tcp:5000`, then in Android Studio
   **Sync Project** and **Run**.

## Try it
1. Habits tab: add "Coding practice", every day, 45 min. Add "Aptitude practice", times a week = 2, 60 min.
2. Tasks tab: add "Assignment", High, 120 min, due tomorrow, and "Reading", Low, 60 min, no due date.
3. Home tab: you should see sessions placed in your free time. Check:
   - nothing overlaps your classes or meals,
   - the Assignment is split into sessions of at most 90 minutes if free time is tight,
   - Reading (no deadline) comes after the urgent things.
4. Tick the coding practice session. It moves to "Done today" and disappears from the plan.
5. Tick a task session: the Tasks tab shows its progress.
6. Make today overloaded (add a long class). The "Not enough time for everything" card appears.
7. Check another day with the chips: Aptitude practice appears on its scheduled days.

## Run the unit tests in Android Studio
Open `app/src/test/java/.../domain/` and click the green arrow beside `SchedulerTest` or `DayPlannerTest`.

## If something goes wrong
- Crash on launch mentioning "Migration didn't properly handle": uninstall the app, run again.
  Your data is on the server and comes back after sign-in.
- Offline message: run `adb reverse tcp:5000 tcp:5000` again, keep the VPN off, tap Sync.

## Known limits (planned for later phases)
- The plan is recomputed live from your data; you can't yet drag a session to another time
  (the chatbot in phase 6 will do "move this to the evening").
- Task progress is only recorded when you tick a session or finish the task.
- No reminders yet (phase 4).
