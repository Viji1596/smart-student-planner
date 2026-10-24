# Phase 6k: the repeating question, and swapping / moving WHOLE tasks

## The repeating "Which session do you mean?" (your screenshot) was my bug
When you answered "first", nothing happened. The phone's session ids look like `2026-10-24|TASK|<id>|1160`, and the
markers that carry your choice only accepted letters, digits and dashes. So on the phone the choice was lost and the
question was asked again. (My tests used simple ids like `s1`, so they passed. The tests now use the phone's real format.)

## "When I ask to swap, swap the entire task, not the slot"
Your screenshot was one task, "Deep learning project", planned as two sessions (19:20-19:50 and 20:30-22:00). The
assistant treated those as two things, and asked which one.
- **Naming a task means the whole task**, however many sessions the plan has split it into. It no longer asks "which
  session?" about one task. It only asks when there are really two different tasks with the same name, and then it
  describes each by all its times and its due date.
- **Swap** exchanges the whole tasks: all the time the two tasks have together is shared out again, the one that was later now
  comes first, and each keeps exactly its own minutes. Example: Deep learning project (19:20-19:50, 20:30-22:00) and MLP project
  (22:10-23:10) become MLP 19:20-19:50 + 20:30-21:00, then Deep learning 21:00-22:00 + 22:10-23:10.
  Other tasks in between stay where they are. A task may be cut where a slot ends. The result stays where you put it when the
  plan is rebuilt. Sessions that have already started don't move.
- **Move to a time** ("move deep learning project to 11pm"): all of its sessions go back to back from that time. If they
  don't all fit, nothing moves and it says why.
- **Skip** a task skips its next session.
- **Pointing at a slot** still works on that one slot: "swap the first and the second", "swap the 4pm one and the 7pm one", "move the 8:30
  one to 11pm". A name and a slot together: the slot decides.
- AI mode is told the same.

## Tests
Backend 332 (new: real ids with | and :, one task in two sessions swapped / moved / skipped without any question, two
different tasks with the same name each in two sessions, the new actions' validation). App logic 247 (new: 12 tests of
the swap and move logic, including 400 random scenarios: each task keeps its minutes, nothing overlaps, nothing leaves the time
they had). The phone-side wiring (the plan manager and the chat's action runner) is new code I cannot run here; I checked
every file for missing imports.

## Update steps
1. Copy the zip over your project. 2. `git add .`, `git commit -m "Phase 6k"`, `git push`; `/health` must say 6.8.
3. Android Studio: Sync, Run.
