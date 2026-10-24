# Phase 6: the assistant (chat)

Open it from the **face button on Home** or from **"+" -> Ask the assistant**. Type what you want, in plain
English. It works **free, with no API key**. If the server has an AI key, the AI is used for freer wording,
and the free assistant is the automatic fallback.

## What you can say
- **Add:** "add task maths homework 45 min due friday high priority", "remind me to call the bank tomorrow",
  "I have to submit the lab record in 3 days", "add habit gym on mon wed fri 1 hour",
  "add habit coding practice every day 45 min", "new habit aptitude twice a week".
- **Finish:** "I finished maths homework", "done with reading", "mark physics done" (a part of the name is
  enough when it points to one task), "I did coding practice", "I worked on maths homework for 30 minutes".
- **Change:** "change maths homework due date to monday", "make maths homework 90 minutes",
  "set reading to high priority", "rename reading to read chapter 5", "remove the due date from reading".
- **Move a session today:** "move physics to 6pm", "put maths homework at 7:30 pm", "move reading to the
  evening". The session is then **pinned**: it shows "Moved by you" and stays where it is when the plan is
  rebuilt; everything else is planned around it.
- **Skip / re-plan:** "skip physics", "do maths homework later", "re-plan my day".
- **Ask:** "what's my plan today / tomorrow?", "how much free time do I have tomorrow?", "what's due?",
  "what's overdue?", "what did I miss today?", "show my tasks". Say "help" for examples.
- **Delete:** "delete reading". **It always asks you to confirm first**, whatever the AI says.

If a name matches several tasks ("mark chemistry done" with "Chemistry quiz prep" and "Chemistry
assignment"), it asks which one, with tap-to-answer buttons.

## What it will refuse, and say so
- Moving a session onto a class, a meal, travel, rest or the morning routine, onto another session you placed,
  or into the past. It names the clash and offers the next free slot. "Around the evening" means the nearest free slot.
- Moving to another day: sessions can only be moved within today. For another day, change the due date.
- Plans for days other than today and tomorrow.

## How it works (and what's private)
- The phone sends your message, the last few lines of the chat, and a snapshot of your tasks, habits and today's
  and tomorrow's plan. Nothing is stored on the server.
- The server only **interprets** and returns structured actions. The phone checks them again and does the work
  with the same code as the rest of the app.
- With an AI key, the AI sees that same snapshot. Task titles are treated as data, not instructions.
  It can only request the actions above (at most 5 per message); deleting still needs your tap.

## Also in this update
- **"Check connection" button removed** from the timetable import screen (as you asked). Import errors still
  show their details, which is handy if anything goes wrong again.
- `/health` now shows `"version":"6"` (and that chat exists).
- The app's database moves to version 6 (a `pinned` column for moved sessions). Your data is kept.

## Optional: turn on the AI for freer wording
On Render -> Environment add `ANTHROPIC_API_KEY` (your key). Optional: `CHAT_MODEL` (default `claude-haiku-5-5`,
the cheaper one). Leave it unset to stay free. The same key also enables the AI reader for photos.
If the AI is down or out of credit, the free assistant answers and says it used "basic mode".

## Tests
- Backend: 153 tests (57 new): dates/times/durations in plain English; 29 assistant phrases (including
  partial names, ambiguity, "remove the due date" never being a delete); the strict action checks; the AI
  request and answer handling with a fake AI (bad tool calls dropped, delete always confirmed, max 5 actions,
  falls back when the AI fails); the endpoint with and without an AI key.
- App logic: 138 tests (16 new): a moved session stays put on re-plan, is planned around, makes no travel,
  is dropped when its task is done, and "can this go there?" (class named, meal, past, outside the day,
  another moved session, evening = nearest slot, a 300-run random check that a move is never on a busy block).
- Verified against the real `app.js` with a fake database: health, add/move/delete/question, login required.
- **Not tested by me:** the Android screens and the phone-side code that carries out actions (it compiles
  against the same repositories, but I can't run the app). Please try the checklist below.

## Update steps
1. Copy the zip over your project.
2. Backend: `git add .`, `git commit -m "Phase 6"`, `git push`. Wait for **Live**, then open `/health`:
   it must show `"version":"6"`.
3. Android Studio: File -> Sync Project with Gradle Files, then Run.

## Try this
1. Home -> face button. Say: "add task test chat 20 min due tomorrow". The task should appear in Tasks.
2. "what's my plan today?" and "how much free time do I have tomorrow?"
3. "move <one of today's planned tasks> to 8pm". Home should show it at 20:00, "Moved by you".
   Then "re-plan my day": it should stay at 20:00.
4. "move <a task> to <a time inside a class>": it should refuse and name the class.
5. "delete test chat": it should ask for confirmation; after "Yes, do it" the task is gone.
