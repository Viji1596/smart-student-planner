# Phase 6h: the Done bug, grades and trends, bedtime summaries, themes, the Assistant tab

## 1. "7.3 and 7.7 are in Earlier today as Done, but I never clicked Done"
**Cause (a real bug of mine):** un-ticking a finished task kept `spent = estimate - 1`, so it came back with exactly
**one minute** left. That is where the 1-minute sessions ("Integration 7.3, 1 min") came from. Un-ticking also never
removed the "Done" line that ticking had created, and there was no way to undo a wrong tick (the checkbox on the Tasks tab
and the big tick button on Home are easy to hit by accident).
**Fixed:**
- Un-ticking a task now restores the progress it had before it was finished (remembered when it is finished), never
  "one minute short", and removes the Done line it created.
- **Every "Done" line under Earlier today has an Undo button.** It takes the work back off the task (the task is open
  again with the right amount left) and the plan puts it back.
- **After ticking a session on Home, an "Undo" bar appears for 8 seconds.**
- To clean up your two tasks now: on Home, under Earlier today, press **Undo** on "Integration 7.3" and "Integration 7.7".
  They reopen with their full time, and the 1-minute sessions turn into proper ones.
(I first tried "a minimum 5-minute session", but that would plan more minutes than a task has left, which the planner's
tests forbid, so I fixed the cause instead.)

## 2. "The chatbot says the server is running an older version"
It is. The app is newer than the backend you have on Render, so some things the app sends or expects are missing
(that is also why "what should I focus on" got "I don't understand"). **Update the backend:**
1. In your project folder run `git add .`, then `git commit -m "Phase 6h"`, then `git push`.
2. In the Render dashboard wait until the service says **Live**.
3. Open `https://<your-service>.onrender.com/health`: it must say `"version":"6.5"`.
The chat title shows the server version, and the app posts this same explanation in the chat when the server is old.
**New:** read-only questions are now answered **on the phone** when the server doesn't understand them or is old or
unreachable: how am I doing, my report / grade, what should I focus on, my plan, free time, what's due, overdue, missed.
"How is my day working/going" is a report for **today**.

## 3. Light and dark mode
Profile -> **Appearance**: Match my phone / Light / Dark. It applies at once and is remembered.

## 4. The Assistant is in the bottom bar
Home | Tasks | + | **Assistant** | Progress | Profile. The face button in Home's header is gone.

## 5. Grading, finished against scheduled, deadlines (Progress tab)
A new **"How you're doing"** card (Today / This week / This month):
- **Finished against scheduled:** how many of the tasks scheduled on each day were finished ("6 of 8"), as a bar.
  A task counts once per day however many sessions it has.
- **Within the deadline:** finished on time, finished late, and missed. A task is on time up to **30 minutes after the end of
  its due day** (a task due Thursday finished at 00:20 on Friday is on time; at 00:31 it is late). Open tasks past
  that moment are missed.
- **A grade:** half "finished what was scheduled", half "met deadlines" -> score 0-100 -> A+ (90), A (80), B (70),
  C (60), D (50), E.
- **Day by day** bars, and **"What I notice about how you work"**: your strongest and weakest weekday, the time of day you finish
  more in, whether you finish close to deadlines or ahead of them, a task that keeps slipping, and how steady you are.
  (Only said when there is enough data behind it.)
**Limits, honestly:** the app keeps session history for 60 days and remembers exact finishing times only from the
earlier updates onward; older tasks are judged by the day they were finished. The first days therefore have thin numbers, and the
report says so.

## 6. The chatbot gets all of it
The numbers and insights are sent to the assistant. Ask "how am I doing", "my report", "what's my grade", "weekly report",
"monthly analysis", "how did I do today". Basic mode answers with e.g. "Congratulations! You finished 6 of 8 scheduled
tasks (75%)" (80% or more is congratulated, 50-79% "good progress", below that gentle), the deadline counts, the grade
and the insights. In AI mode the AI is instructed to congratulate only when deserved, use only the real numbers, analyse
the student's behaviour from the insights, and finish with one concrete suggestion.

## 7. Bedtime notifications, weekly and monthly analysis
- **Every day at your bedtime** (Profile -> Daily routine): "Today: 6 of 8 tasks done · Grade B", with on time / late / missed.
- **Sundays at bedtime:** the week's analysis. **The last day of the month at bedtime:** the month's analysis.
- All are kept under **Progress -> Summaries** (tap to read the full analysis).
- Each can be switched off under Profile -> Reminders, which also has **"Show today's summary now"** to try it at once.
- They need Reminders switched on and exact alarms allowed, like your other reminders.

## Tests
- Backend: 292 tests (new: the AI's instructions for congratulating and analysing, the graded report accepted and refused
  correctly, "how is my day going" = today, "monthly report", "daily report", "what's my grade", "analysis").
- App logic: 224 tests (new: the 30-minute grace to the minute, on time / late / missed, scheduled against finished per day,
  the score and every grade boundary, all the insights, congratulation wording, the notification text, undoing a Done,
  the progress rules for un-finishing, the bedtime schedule (today or tomorrow, after midnight, Sundays, last day of the
  month, each switched off), phone-side questions, server version comparison).
- **Not tested by me:** the Android screens and the phone-side wiring (theme, bottom bar, Progress card, undo bar,
  notifications and alarms): I cannot run the app. I checked every changed file for missing imports.

## Update steps
1. Copy the zip over your project. 2. **Backend:** `git add .`, `git commit -m "Phase 6h"`, `git push`; `/health` must say 6.5.
3. Android Studio: Sync, Run (the database moves to version 8, your data is kept).
4. Press Undo on Integration 7.3 and 7.7 under Earlier today.
