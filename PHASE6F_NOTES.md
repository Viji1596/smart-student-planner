# Phase 6f: keywords anywhere, and "how am I doing" from day one

## "How am I doing" 
On the server the exact phrase already worked, so something else made it look broken. Two real problems found:
- **8 of 29 ways of asking failed**, and two gave a wrong answer ("how did i do today" -> "I couldn't tell which task
  you finished", because the word "did" was taken for "I finished it"). All 29 now work, including "how i am doing",
  "how am i performing", "progress", "am i on track", "how was this week", "how much did i study this week",
  "am i falling behind", and a day / two weeks / a month ("how did i do today", "report for the last 14 days").
- **Right after the update there is almost no history**, and the report opened with a discouraging line. Now it is
  useful from the first day: "Today so far: 45 min done, 3 h still planned", your tasks (finished / open / overdue /
  due soon / on time), each habit's count and streak, and an honest note that session history fills in from now on.
  A day with only a plan (nothing finished or missed yet) no longer counts as history. "The last 1 days" is now
  "today". A one-day report no longer talks about "strongest day" or "a steady week", and praise or warnings need a few
  days of data behind them.

## Your keyword idea
Before, the assistant matched whole sentence shapes that had to start a certain way, so any extra word broke it.
Now there is a second layer that looks for keywords ANYWHERE in the sentence (the careful patterns still go first):
- **"add" (or create, note down, remind me to, todo) anywhere** adds a task:
  "MLP project high priority due tomorrow please add it", "viva prep, priority low, due next friday, add it".
- **Everything after "due" / "deadline" / "submit by" is the date.** "due friday", "deadline: 30th", "due 28",
  "due next monday", "due nxt frday". The due words never stay in the title. If the date can't be read
  ("due blah blah") it **asks** (Due tomorrow / Add without a due date) instead of quietly losing it.
- **Priority is read on both sides of the word "priority"**: "high priority", "priority high", "priority: low",
  "priority is medium", "low prio", "p1/p2/p3", "priority 1/2/3", and "urgent / asap / top priority / very important".
  The priority words never stay in the title; "low level design" and "high school reunion" are left alone.
- **Just naming a task** ("mlp project") offers what to do with it: Mark it done / Move its session / Do it tomorrow /
  Delete it (which still asks for confirmation).
- **Adding a name you already have asks** ("You already have MLP Project, due Sun 25 Oct. Update that one, or add
  another?") instead of quietly making a twin or quietly editing the old one.
- Deleting is never keyword-guessed: it still needs a clear command and your "yes".

The trade-off, honestly: the more forgiving it is, the more it may guess. That is why it shows what it understood
("Got it, adding the task X, due Sun 25 Oct, high priority") and why every guess that matters asks first.

## Tests
- Backend: 258 tests (new: every way of asking for the report, questions never completing tasks, the add keyword
  anywhere, due-date reading and asking, priority on both sides, duplicates, naming a task, long unrelated
  sentences left alone). App logic: 175 tests (new: the report from day one, one-day wording, praise needs data).
- Not tested by me: the Android screens, and the AI mode.

## Update steps
1. Copy the zip over your project. 2. `git add .`, `git commit -m "Phase 6f"`, `git push`; when Render says Live,
`/health` must show `"version":"6.3"`. 3. Android Studio: Sync, Run (the report wording is in the app).

## If "how am I doing" still looks wrong
Tell me exactly what you typed and what the chat answered, and open `/health` to check the version.
