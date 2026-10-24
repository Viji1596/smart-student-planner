# Phase 6j: "swap and move are not working" (the endless "Which one do you mean?")

## What was wrong (three things, found by reproducing your screenshot)
1. **Two tasks with exactly the same name** ("Deep learning project" twice). The question offered two chips that both said
   "Deep learning project", and answering with the name can never tell them apart.
2. **My answer-handling made it worse:** each time you answered, your answer was glued onto the end of your sentence, so
   it grew ("move Deep learning project to tomorrow Deep learning project Deep learning project...") and asked again.
3. **Even for different names the chips were broken for "move/swap" sentences.** The chip glued the name onto the end:
   "move physics to tomorrow Physics lab". But the planner reads the name from the words *before* "to", so the added name
   was ignored and it asked again. So any vague "move physics to..." or "swap physics with maths" could never be completed.

## What it does now
- A choice carries **which exact item you chose, and which ones it was not**, whatever the sentence looks like. The chat shows
  the label (for example "Deep learning project · due Fri 30 Oct · 1 h"), never the internal marker.
- When names are identical the question lists what differs (the due date and length, or the session's time and due date):
  "You have 2 called "Deep learning project". Which one do you mean? 1. due Tue 27 Oct · 2 h  2. due Fri 30 Oct · 1 h".
- You can also answer in words: **"first" / "second" / "2" / "the last one"**, **"the one due Friday"**, **"30th"**,
  or **the name** when the names differ ("physics homework").
- Saying the same name again no longer loops: it says "That doesn't tell them apart" and shows the choices again.
- A progress note about a name that matches two tasks ("worked on maths for 30 min") now asks which one, instead of
  creating a junk task called "Worked on maths".
- Two vague names in one sentence ("swap physics with maths") are asked one after the other, and the right pair is swapped.
- In AI mode the AI is told what the markers mean.

## Tests
Backend 322 (new: the screenshot case; chips for duplicates; first / second / last; by due day, date; no loop even after 4
repeats; swap with look-alikes, both vague; a sweep of 25 kinds of command with vague names where every chip must lead to an action;
the endpoint; the AI prompt). App logic unchanged (235). The change on the phone is small (a tapped choice shows
its label and sends its exact choice); I cannot run the Android app, but I checked every file for missing imports.

## Housekeeping you may want
You probably created the duplicate "Deep learning project" while testing. On the Tasks tab you can delete the one you
don't need; the assistant works fine either way now.

## Update steps
1. Copy the zip over your project. 2. `git add .`, `git commit -m "Phase 6j"`, `git push`; `/health` must say 6.7.
3. Android Studio: Sync, Run. (The app warns in the chat if the server is older than 6.7: the new choices need it.)
