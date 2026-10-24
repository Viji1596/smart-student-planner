# Phase 6d: Home keeps up with what the assistant changes

## The problem
After the assistant changed something, the other page showed it but the Home dashboard did not.

## What I found, and what I could not find
Home is built from the **saved plan**, which is rebuilt by a background watcher (800 ms after any change).
Only some assistant actions (move, swap, put off, lighten) also rebuilt the plan immediately; adding or
finishing a task, or a habit, relied on the watcher alone. If that watcher ever stopped, Home would keep
showing an old plan while the Tasks list looked right. I read every screen's refresh path and found no other
break, so I can't prove this was the cause. I changed everything on that path so it cannot happen silently.

## What changed
- **After every assistant change to tasks or habits, today's plan is rebuilt straight away**, not after a delay.
- **The background watcher restarts itself** if it ever fails (before, it would stay stopped until the app
  was reopened) and records why.
- **Home refreshes whenever it appears**, including when you come back from the chat, not only when the app
  returns from the background.
- **The assistant says where a new task landed**: "It's in today's plan at 17:00-17:45", or "It isn't in
  today's plan (it suits another day better, or today is full). It will show under the next days on Home."
  So an added task that is planned for another day no longer looks like a missing one.
- **If the plan can't be refreshed, the chat tells you why** ("I saved that, but couldn't refresh today's
  plan: ..."), and the same error is recorded in Profile -> Reminders -> Status.

## If it still happens
Please tell me: the exact thing you typed to the assistant, which page showed it, and what Home showed
instead. Two quick checks help a lot:
1. On Home, tap **Re-plan**. If Home then matches, the plan was just stale.
2. Profile -> Reminders -> Status: is there a red error line? Send me its text.

## Update steps
App only (the backend is unchanged): copy the zip over your project, File -> Sync Project with Gradle Files, Run.
