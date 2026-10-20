# Phase 5b: a missed task is one line, not many

## What was wrong
Every time a session was missed, a separate "Missed" record stayed in "Earlier today". A task missed three
times showed up three times, each with its own "I did it" button. The task itself was only planned once,
but you had to press "I did it" for every miss.

## What changed
- **One line per task.** Repeated misses of the same task (or habit) are one line: "Missed 3 times", with
  the time of the latest attempt and a single **I did it** button. Different tasks stay separate lines.
- **I did it, once.** It records one session's worth of work (not three) and clears all the repeats.
- **Done clears the misses.** When you finish the session that is planned now (with the check button, or the
  Done button on a "Time's up" notification), that task's older missed records are removed too.
- **Finished work has no missed line.** If the task is completed (or the habit is done today), nothing
  stays under "Missed".
- Done sessions of one task are also one line ("Done · 2 sessions").
- Still true: the task appears in the plan once, and you get at most 2 "Missed" notifications a day for the
  same task; after that it is re-planned quietly.

## Tests
- 122 planning tests (new: a task missed 3 times is one line recording one session of work, separate tasks
  stay separate, missed lines for finished work disappear, done sessions are summed).
- 62 backend tests (backend unchanged since phase 5).

## Update steps
1. Copy the zip over your project.
2. Android Studio: File -> Sync Project with Gradle Files, then Run (phone connected). Your data is kept.
3. Backend: only if you haven't pushed phase 5 yet (git add ., git commit, git push); nothing new here.

## How to check
1. Add a 5-minute task and ignore it for a few cycles (each takes about 10 minutes), or use "Later" on its
   session several times.
2. Under "Earlier today" it should be one line: "Missed N times", with one "I did it" button.
3. Press the check button on the session that is planned now: the missed line should disappear and
   "Done" should show.
