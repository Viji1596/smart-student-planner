# Phase 6c: the assistant understands how people really type

Server-only update: you do NOT need to rebuild or reinstall the app. Push the backend and wait for Render.

## What was wrong
"i want to add a high priority task MLP Project deadline tommorrow" was not understood, and the nearby form
"add a high priority task MLP Project deadline tommorrow" only looked like it worked: it added the task but
silently lost the deadline (the typo "tommorrow" was not a date) and left "deadline tommorrow" in the title.
Even spelled correctly, the word "deadline" stayed in the title.

## What changed
- **Typos in the words that matter are corrected**: tommorrow, tomorow, tmrw, 2morrow, wensday, thrusday, evning,
  tonite, priorty, deadlin... Only words very close to the days, parts of the day, "priority", "deadline" etc.
  are touched; task titles are left exactly as typed.
- **Texting spellings and missing apostrophes**: whats, hows, im, dont, cant, wat, pls, plz, thx, u, ur, hw.
- **Samsung/other keyboards' curly apostrophes** (’) and non-breaking spaces are treated as plain ones.
- **More ways to ask**: "i want to / i wanna / i'd like to / i need to / can you / let's / need to ... add a task";
  an adjective before the word task ("a high priority task", "an urgent task"); "add X as a high priority task";
  "add X to my to-do list".
- **"deadline", "due date", "submit by", "submission on", "with a deadline of"** are all due dates and no longer
  stay in the title. "by 25th", "on the 3rd", "due the 30th" are dates ("2nd year" is not).
- **A plain statement is a task**: "exam prep 2hrs tmrw", "i have a viva on monday", "physics notes 45 min",
  "urgent call the bank". If a task with those words already exists it asks whether to change that one or add a
  new one. Plain statements with no date, length or priority, and questions, are never turned into tasks.
- A bare priority at the end counts ("os assignment due fri high"). "add 3 tasks" asks for one task at a time.
- Subject abbreviations are written properly: mlp -> MLP, dbms -> DBMS, os -> OS, dsa -> DSA (only words you
  typed in lower case).
- "mlp project priority low" and "dbms due monday" work without a command word.
- "what time is my mlp session" / "when do i start dbms" are answered from the plan.
- **Fixed on the way:** the assistant's own buttons contained dates like 2026-10-25 that it could not read, so
  tapping "Change the due date" did nothing. They work now, and a new test taps every button it can produce.
- "I need to add the figures to the report" is a task, no longer a request for the performance report.

## Tests
- Backend: 240 tests (about 40 new), including "every button the assistant offers leads somewhere useful".
- Verified through the real server with your exact sentence: title "MLP Project", high priority, due tomorrow.

## Update steps
1. Copy the zip over your project.
2. `git add .`, `git commit -m "Phase 6c"`, `git push`.
3. When Render says Live, `https://<your-service>.onrender.com/health` must show `"version":"6.2"`.
4. No app update needed.
