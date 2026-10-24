# Phase 5: import your timetable (Excel, CSV, PDF, photo) + reminder fixes and diagnostics

## 1. Import a timetable (Profile -> Timetable -> Import)
Choose a file and the app shows what it found. Nothing is saved until you press Save.
- **Excel (.xlsx) and CSV** are read by the server itself, with no AI and no cost. It understands the two
  common layouts: days down the side with periods across the top, or days across the top with periods
  down the side. Time headers can be ranges ("9:00-9:50", "9.00 AM - 9.50 AM", "9-10") or start times
  only ("9:00", "10:00"...). Merged cells and the same subject in consecutive periods become one longer
  class. Lunch, breaks and free periods are left out. A cell with several lines keeps them in the title
  ("CS Lab · Room 204").
- **PDF and photos (JPG/PNG)** are read by an AI model. This needs an API key on the server
  (see DEPLOY.md, "Optional: reading timetables from PDFs and photos"). The review screen warns that AI can
  make mistakes.
- A sheet in a layout the reader doesn't recognise goes to the AI if it is set up; otherwise you get a clear
  message and can add classes by hand.
- **Old .xls files** can't be read: open the file and Save As > Excel Workbook (.xlsx).
- Review screen: tick or untick lines, tap a line to correct its title or times, see warnings and
  overlapping classes. If you already have classes: **Add to them** (skips duplicates) or **Replace them**.
- After saving, the planner immediately works around the new classes (free time, travel, reminders).

## 2. Reminders: fixes and a way to see what is going on
Likely cause of reminders not arriving while the test notification did: Android 14 doesn't let an app set
exact alarms unless a special permission is granted. The app quietly used approximate alarms instead, and
Android can delay those by minutes, which for short tasks looks like "never". Changes:
- The app now declares the permission Android grants automatically for calendar-style apps (USE_EXACT_ALARM,
  and SCHEDULE_EXACT_ALARM on older versions), so reminders are exact without you doing anything.
- **Profile -> Reminders -> Status** now shows: whether Remind me is on, whether notifications are allowed,
  whether exact timing is allowed, whether the battery is unrestricted, when the plan was last checked,
  any error, how many alarms are set, and the next reminders with their times.
- **Send a test alarm in 1 minute** sets a real alarm through the same path as all reminders (the other
  test button shows a notification directly, without alarms).
- **Don't restrict this app's battery use** (some phones stop background apps, which stops alarms).
- Home shows "Reminders are off" with a Turn on button, since they are off until switched on.

## Tests
- Backend: 62 tests (28 new), using real Excel files built in memory: merged cells, AM/PM, start-time
  headers, real Excel time cells, CSV with quotes, a sheet that isn't a timetable, file-type detection by
  content, an invalid/corrupt file, size limits, login required, and the AI request/response handling with a
  fake model (headers, body, errors, fenced answers, bad entries dropped).
- Planning/app logic: 116 tests (new: no duplicates when adding imported classes, overlap detection).
- NOT tested by me: the real AI model call (no API key here), the import screens, and everything on a phone.

## Update steps
1. Copy the zip over your project.
2. Backend: `git add .`, `git commit -m "Phase 5"`, `git push`. Render redeploys itself (a few minutes).
   Check `https://<your-service>.onrender.com/health`.
   Optional: add ANTHROPIC_API_KEY on Render for PDF/photo import.
3. Android Studio: File -> Sync Project with Gradle Files, then Run (phone connected). Data is kept.

## Check reminders (do this before anything else)
1. Profile -> Reminders. Look at Status. Anything with a cross tells you what to fix.
2. Switch Remind me on. Tap "Send a test alarm in 1 minute", close the app, unplug the phone, wait 2 minutes.
3. If the alarm arrives: alarms work, and real reminders should too. If not: read the Status lines, set
   battery use to Unrestricted, and tell me what the Status card says.
4. Add a 10-minute task and look at "Next reminders" in Status: it should list the start, ends-in and
   Time's-up times. If it lists them but nothing arrives, it's the phone blocking alarms (battery settings).
   If it lists nothing, tell me exactly what the Status card shows.

## Check the import
1. Make a small Excel timetable (days down the side, periods across the top with times) and save it as .xlsx.
2. Copy it to the phone, then Profile -> Timetable -> Import -> Choose a file.
3. Review, fix anything, Save. Check Home.
