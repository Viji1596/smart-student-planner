# Phase 6g: a chat that remembers what you were talking about

## "add mlp project" ... "move it to 9pm"
Before, "it" was found by scanning the earlier messages for task names, which is fragile; and even when the task
was found, a task with no session today (like one you just added) was refused: "nothing to move". Now:
- **The app remembers the task you were just working with** (the one the last action added, changed, moved, finished,
  put off...) and sends it with every message. "it", "this one", "that one" mean that task, unless you name another.
  It works in every command: "move it to 9pm", "mark it done", "make it 2 hours", "set it to low priority",
  "do it tomorrow", "delete it" (still asks first), "skip it", "postpone that".
  "this week", "that evening" and "add it" are never mistaken for it.
- **A task with no session today gets one**: "move it to 9pm", "do it after dinner", "put it in the evening",
  "do it now", "schedule mlp project at 6" create a session there (at most 90 minutes, only in free time), and it
  stays put when the plan is rebuilt. If the task already has a session, that one is moved.
- **Short answers to the assistant's questions are understood.** After "Where should I move X?" you can just say
  "9pm", "after dinner" or "tomorrow". After "When should X be due?": "friday". After "How often?": "every day".
  After "What should the task be called?": the name. After "Which task do you mean?": "quiz prep". After "What would
  you like to do with X?": "done", "delete", "postpone", "9pm". A new command is still a new command.
- In AI mode the assistant is told the same focus, so "it" works there too.

## "How am I doing"
On the server the exact phrase has worked in every test, so the failure must be on the way. To make that visible and
to make it work regardless:
- **The chat title now shows the server version** ("Basic mode (no AI) · server 6.4"). If it says "the server is out of
  date: update the backend", that is the problem; or if the version is lower than 6.4, the latest backend isn't
  live yet.
- **The phone answers report questions itself** if the server doesn't recognise them or can't be reached. The report is
  built from data on the phone, so it no longer depends on the server's version.

## Tests
- Backend: 285 tests (27 new): your exact example, "it" in many commands, placing a task (after / before / now /
  part of the day), short answers to every kind of question, fresh commands not mistaken for answers, the endpoint
  taking the focus and returning its version, the AI being told what "it" means. A fake phone ran a whole
  conversation through the real server (add -> move it -> make it 8pm -> add another -> move it -> report ->
  "move mlp project" -> "after dinner").
- App logic: 178 tests (3 new: report questions recognised in many words, not mistaken for commands, day counts).
- Not tested by me: the Android screens and the phone-side execution (checked every file for missing imports).

## Update steps
1. Copy the zip over your project.
2. Backend: `git add .`, `git commit -m "Phase 6g"`, `git push`; when Render says Live, `/health` must show
   `"version":"6.4"`.
3. Android Studio: Sync, Run.
4. Open the chat: under "Assistant" it should say "server 6.4" after your first message.

## Try this
1. "add mlp project" then "move it to 9pm": it is scheduled at 21:00 (Home shows it, "Moved by you").
2. "make it 8pm": it moves. Add another task, then "do it after dinner": that one is scheduled.
3. "how am i doing". If you don't get a report, look at what the title says and tell me.
