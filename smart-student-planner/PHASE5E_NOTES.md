# Phase 5e: find out why the import says "not found"

## What the Render log tells us
Your last deploy succeeded and installed 477 packages. The new backend (with the Excel and PDF libraries) needs
about 486; the old one only 256. So the new code is very likely running, and my earlier idea that Render was
still on an old version was probably wrong. (The browser test I gave could not tell old from new, because both
answer "Authentication required" for that address. Sorry about that.)

## What's new, to settle it
- **Import screen -> "Server: https://..."** shows exactly which address the app is using.
- **Import screen -> "Check connection"** asks the server whether it has the import feature and says what it
  answered:
  - "Connected. The server has the import feature." -> the server is fine.
  - "The server answered 404 (not found): it does NOT have the import feature" -> that address is running an
    old backend, or the app is using the wrong server.
  - "Can't reach the server" -> a network problem.
- **Failures now show "Details"**: the HTTP code and which request it was for (e.g. "HTTP 404 for POST
  /api/timetable/import"), so a failure can be explained exactly.
- **/health** now shows the backend version: open `https://<your-service>.onrender.com/health`. It should say
  `{"ok":true,"version":"5e", ...}`. If it shows only `{"ok":true}`, Render is running an older backend.
- **Excel files now get course names too**: a course list under the table (code, then name) turns
  "23CSE302/Tutorial/Achala" into "Computer Networks (23CSE302) · Tutorial/Achala", like PDFs.
- Excel in the university format (times split over two lines, merged cells, vertical "Interval Break" /
  "Lunch Break" columns, multi-line cells) is tested: 30 classes read correctly.

## Tests
- Backend: 96 tests (new: the Excel university timetable with course names).
- App logic: 122 tests. Not tested by me: the Android screens.

## Update steps
1. Copy the zip over your project.
2. Backend: `git add .`, `git commit -m "Phase 5e"`, `git push`. Wait for Render: **Live**.
   Then open `/health` in a browser: it must show `"version":"5e"`.
3. Android Studio: File -> Sync Project with Gradle Files, then Run.
4. Profile -> Timetable -> Import -> tap **Check connection**, then try your file. If it fails, send a
   screenshot of the error card (it shows Details and Server).
