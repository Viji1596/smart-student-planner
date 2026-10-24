# Phase 6b: a better assistant (moves, open questions, reports, support)

## What was wrong
Only the exact form "move X to 6pm" worked: 11 of 14 natural ways of asking to shift something were not
understood. "Move maths to tomorrow" even changed the task's **due date** instead of rescheduling it.

## Moving things around, in plain English (free mode)
- "move physics to 6" (a bare hour means the next such time still ahead today), "shift it to 7:30",
  "put reading after dinner", "move reading before physics", "move maths to the evening", "do it now",
  "move the 4pm session to 8", "shift my first/last/next session to 8 pm", "swap maths and reading",
  "put reading in the slot of maths".
- **Another day = put the task off** ("move maths to tomorrow", "postpone reading", "do it on friday"):
  it will not be planned before that day, and its due date is **not** touched. If that would be past the
  due date, it warns you and offers to change the due date too. "Start maths today" / "bring back reading"
  undoes it. Put-off tasks show "starts Fri 23 Oct" in the Tasks list.
- "Move reading" with no destination asks where, with tap-to-answer free slots.
- It remembers the conversation: "move it to 9pm", "I finished that", "swap it with maths".
- It ignores filler at the start: "ok. can you...", "actually swap...", "hey, add task...".
- Swapped and moved sessions stay where you put them when the plan is rebuilt ("Moved by you").

## Open questions and a friendlier voice
- **Basic mode (no AI key):** much better at everyday wording, remembers context, answers from your data
  ("what's next?", "how many tasks do I have?", "when is maths due?", "how long is physics?"), small talk,
  greetings that mention your next session. It is still pattern-based, so it cannot hold a truly open
  conversation. It says so under the chat title ("Basic mode").
- **AI mode (optional):** with an AI key on the server the assistant has a proper personality (warm, brief,
  like a good senior), sees your tasks, plan, free time and weekly report, and can talk about anything in that
  data in its own words, using the same safe tools. The title says "AI mode". If the AI is down, the basic
  assistant answers automatically.
  To turn it on: Render -> your service -> Environment -> add `ANTHROPIC_API_KEY` (from console.anthropic.com).
  Optional: `CHAT_MODEL` (default `claude-haiku-5-5`, the small, cheap one). Check Anthropic's pricing page: with
  this small context a message is a small fraction of a cent on the small model, but I haven't verified current prices.

## Performance report ("how am I doing?")
Say: "how am I doing", "weekly report", "how was my week", "report for the last 14 days", "my streaks".
It shows: work done and sessions finished, how that compares with the period before, your strongest day,
what was missed (a task missed three times is one miss, and not a miss if you did it later), tasks finished /
open / overdue / due soon and how many were on time, each habit's count and streak, and one suggestion.
In AI mode the assistant also writes it in its own words from the same numbers.
**Honest limits:** the app now keeps 60 days of session history and remembers when you finish a task, but only
from this update on, so the report is thin for the first days and says so. Habits use the history you already have.

## When you feel overwhelmed (a supporter, not a therapist)
- Say things like "I'm overwhelmed", "too much to do", "I can't cope", "I'm burnt out", "no motivation",
  "I keep procrastinating", "I'm so tired", "I'm scared about my exam", "I just want to talk". It answers
  warmly, shows the **three things to start with** (most urgent first, with a 10-minute first step), and offers:
  **Lighten today's plan**, **I need a break**, **Just talk**.
- **"Lighten my day"** keeps the most urgent work (overdue and due-today work is never put off, at least one task
  stays), and shows exactly which tasks would move to tomorrow and asks you to confirm. Nothing is deleted. Say
  "I only have 2 hours today" to set the budget. Bring any task back with "start <task> today".
- **"What should I focus on?"** shows the top three with a tiny first step.
- **If a message suggests self-harm** ("I want to die", "hurt myself", ...) it drops everything else: no tasks,
  no plans, no productivity advice. It answers with care, encourages you to tell someone you trust today, and
  gives: India's emergency number **112** and the free 24x7 **Tele-MANAS** helpline **14416**
  (or 1-800-891-4416), checked against government sources; outside India, your local emergency number or crisis
  line. In AI mode the AI is instructed to do the same, with no tool calls.
- It never diagnoses and says it is not a therapist.

## Tests
- Backend: 211 tests. New: bare hours, 20+ phrasings of moving, swapping, after/before, putting off,
  remembering the conversation, fillers, support and crisis messages, reports/focus/lighten, answers from data,
  the AI's instructions and tools, the endpoint. Verified through the real `app.js` with a realistic
  multi-turn conversation.
- App logic: 160 tests. New: putting a task off, ranking what matters most, lightening a day, the report
  (minutes, misses, trend, tasks, on-time, habit counts and streaks), the wording.
- **Not tested by me:** the Android screens and the phone-side code that carries out the actions (swap, put off,
  lighten, report): they use the same repositories and planner as before, and I checked every file for missing
  imports, but I cannot run the app. Also not tested: the AI itself (I have no key here).

## Update steps
1. Copy the zip over your project.
2. Backend: `git add .`, `git commit -m "Phase 6b"`, `git push`. When Render says **Live**, open
   `https://<your-service>.onrender.com/health`: it must show `"version":"6.1"`.
3. Android Studio: File -> Sync Project with Gradle Files, then Run. The database moves to version 7
   (two new columns for tasks); your data is kept.

## Try this
1. "move <a task> to 6" -> it moves; "swap <task A> and <task B>"; "put <task> after dinner".
2. "move <a task> to tomorrow" -> it leaves today and appears in tomorrow's plan; Tasks shows "starts ...".
3. "how am I doing?" (thin at first, fills over the week).
4. "I'm overwhelmed" -> warm reply, top three, chips. Tap "Lighten today's plan" -> confirm card -> "Yes, do it".
5. "start <task> today" brings a put-off task back.
