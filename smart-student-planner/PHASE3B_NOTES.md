# Phase 3b: duplicate-task fix + new look

## Fix: a task due tomorrow no longer shows on both today and tomorrow
Each day used to be planned on its own, so an unfinished task appeared on every day until done.
Days are now planned in order (today first) and work placed on an earlier day is not planned
again later. Result:
- A task due tomorrow is planned once: today if there is room, otherwise tomorrow.
- A big task is spread over the days left (e.g. 300 min due in 4 days = 60 min a day) and the
  total never exceeds what is left to do.
- A "2 times a week" habit lands on Monday and Thursday only, counted across the week.
- Late in the evening, work that no longer fits today moves to tomorrow.
The "Not enough time" card is red only when something due today (or overdue) can't fit.
Anything due later says "Continues on later days".
Covered by unit tests (49 in total, including this exact case and 100 random scenarios).

## New look (based on your screenshot)
Lavender background, white rounded cards, one purple accent, dark and light pill labels,
round buttons, big headings. Light and dark mode both supported.

Bottom bar: Home | Tasks | (+) | Progress | Profile

- Home: greeting, "Start today's tasks.", a week strip (tap a day), four tiles
  (Planned, Habits, Open, Past due), then the plan as cards. Tick the round button when a
  session is finished.
- Tasks: "Your tasks" with filters (All Tasks, Open, Completed, Overdue), cards with a due-date
  pill and a duration pill, round button to complete, menu to delete.
- (+): choose New task, New habit or New class.
- Progress: week strip, a bar chart of habits done each day, tasks done / still open,
  and a "Week ahead" chart of planned work.
- Profile: your name, then Daily routine, Timetable and Habits (each with a back button),
  and Sign out.

Differences from the screenshot, on purpose:
- The bell is a Sync button (there are no reminders yet; those arrive in phase 4).
- The avatar shows your initial instead of a photo.
- The "Focus mode" card on Progress becomes real in phase 7. For now that chart shows habits.
- "Goals setup" and "On Hold" tiles don't exist in the app yet.

## Update steps
Copy the zip's contents over your project (replace when asked). No backend changes in this update,
so nothing to migrate. In Android Studio: Sync Project, then Run.
If the phone isn't detected, run `adb devices` and see the earlier steps.
