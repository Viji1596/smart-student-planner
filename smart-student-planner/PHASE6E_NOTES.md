# Phase 6e: work you finish by talking (or on the Tasks tab) now shows under "Earlier today"

## The problem
"Earlier today" on Home lists the planned sessions you tick off on Home. When you told the assistant "I finished
maths homework", it marked the task as done, but nothing recorded that a session was completed, so it never
appeared there. The same was true when finishing a task on the Tasks tab.
It also meant that work finished that way was left out of the performance report.

## What changed
- Finishing a task, logging time on a task, or marking a habit done **through the assistant**, and finishing a task
  **on the Tasks tab**, now works like ticking its session on Home:
  - if the task had a planned session today, that session becomes **Done** and is shown as just finished;
  - if it didn't, a finished session is added (the time you logged, or what was left of the task);
  - older "missed" lines for that task disappear, so nothing is left to press "I did it" on.
- It appears under **Earlier today** straight away and counts in **how am I doing?** (minutes done, sessions
  finished, on-time rate).
- Ticking on Home is unchanged (it already worked).

## Tests
- App logic: 168 tests (8 new): a planned session becomes done and is shown as just finished, no planned session
  gives a finished one, only the earliest planned session is used, missed lines of that task disappear but not of
  other tasks, it shows in "Earlier today" and in the report, early-morning and very short times, recording twice
  never duplicates.
- Backend unchanged (240 tests pass).
- Not tested by me: the phone-side wiring (I checked every file for missing imports, but I cannot run the app).

## Update steps
App only: copy the zip over your project, File -> Sync Project with Gradle Files, Run.

## Try this
1. Say "I finished <a task>" to the assistant: Home -> Earlier today should show it as Done.
2. On the Tasks tab, tick a task: it should appear there too.
3. "how am I doing?" should count both.
