# Phase 6i: travel around meals, no travel slot or notification, "what to focus on", a calmer bottom bar

## Travel
- **Classes after lunch:** the trip to college would run into lunch, so it now moves earlier and ends when lunch starts
  (you arrive, then eat at college). Example: class 14:00, 40 min trip, lunch 13:00-13:40 -> trip 12:20-13:00.
  It is never moved into your morning routine; if it can't fit before the meal, the overlap is reported as before.
- **The trip home** that would run into a meal now starts after the meal (you eat at college, then travel).
  (Not something you asked for in so many words: it is the mirror of the rule above. Tell me if you'd rather not.)
- **A rest block that sits on a meal** (the "Rest overlaps Dinner" warning you saw) now starts when the meal ends.
- **Travel is not a slot any more:** the time stays blocked (no work is planned in it) but it isn't shown on Home or listed
  by the assistant.
- **No travel notifications:** "time to leave" is gone, and so is its switch under Reminders.

## "What to focus on"
Your exact words failed, and so did 30 of 45 other natural ways of asking ("what to do", "where to start", "what's my
priority", "which one first", "most urgent", "priorities", "I don't know where to start"...). The server and the phone now understand
all of those, with typos. It does not hijack commands ("add task focus on dsa", "can you please add a low
priority task ..."), statements ("I can't focus"), questions about one of your tasks, or other days
("what should I do tomorrow"). "What's next" still shows your next session.
Since the phone has the same rules, this works even if the server is out of date.

## The bottom of the screen
The bar went from 5 to 6 slots when the Assistant joined. Now: **five equal tabs** (Home, Tasks, Assistant, Progress,
Profile) with a soft pill behind the selected one; the **"+" is a floating button** above the bar, on Home and
Tasks only; the lists have extra room at the bottom so nothing hides behind the bar or the button.
(I took "the down part" to mean the bottom bar. If you meant something else, tell me which part.)

## Tests
Backend 301 (new: all the ways of asking for focus, typos, what must NOT trigger it); app logic 235 (new: trip before lunch,
trip home after dinner, a trip not pushed into the morning routine, rest after dinner, travel hidden from the timeline,
no travel reminders, the same focus phrases on the phone). Not tested by me: how the new bottom bar looks on your phone.

## Update steps
1. Copy the zip over your project. 2. `git add .`, `git commit -m "Phase 6i"`, `git push`; `/health` must say 6.6.
3. Android Studio: Sync, Run.
