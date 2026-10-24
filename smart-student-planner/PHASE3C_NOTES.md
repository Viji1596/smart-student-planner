# Phase 3c: travel time, rest and breaks, deployment prep

## 1. Travel time (Profile -> Daily routine -> Travel)
- "To college" and "Back home" minutes are separate, because the way there and back often differ
  (a 30 minute walk to the hostel both ways, or a bus that takes 55 minutes in the morning and
  70 in the evening).
- "Arrive early by" is how long before the first class you want to be there.
- Travel is added only on days that have classes: before the first one and after the last one.
  Days with no classes get no travel.
- Leave both at 0 to turn travel off.
- If you'd have to leave before your morning routine ends, the Home screen shows an
  "Overlapping items" warning (e.g. "Travel to college overlaps Morning routine").
- For one-off or unusual trips (walking back for lunch, a bus to the library), add them as a normal
  entry in Timetable, e.g. "Walk to hostel" 13:00-13:30 on the days you do it.

## 2. Rest in the middle (Profile -> Daily routine -> Rest and breaks)
- **Rest at / Minutes:** a protected rest block in the day (a nap, or time to switch off).
  Nothing is planned in it. Minutes = 0 turns it off (that is the default).
- **Short break between sessions:** the gap left between back-to-back study sessions (default 10 minutes, 0 = none).
- **Long break after / Long break:** after this many minutes of study in a row, the gap becomes a longer
  rest (default: 20 minutes after 2 hours of work; 0 = no long breaks).
- Breaks are shown on the Home plan ("10 min break"), so you can see the rest you've been given.
- Time spent in classes, meals, travel or rest counts as rest: the work counter starts again after them.

## 3. Deployment prep (so the app can run without USB and without your PC)
- `render.yaml` describes the backend for Render (Free plan).
- `DEPLOY.md` is the step-by-step guide: GitHub -> Render -> new APK.
- Backend: trusts Render's proxy (so rate limiting is per person), Prisma CLI moved to normal
  dependencies (needed at build time), Node version set.
- App: waits up to 90 seconds (a sleeping free server plus a sleeping database), shows
  "Waking up the server..." when a sync is slow, and shows a clearer reason when sync fails
  (can't reach the server / server problem).

## Tests
- 68 planning tests (new: travel, rest, long breaks, random scenarios that check breaks never overlap work).
- 34 backend tests.

## Update steps
1. Copy this zip's contents over your project (replace when asked).
2. Backend (VPN off):
   ```powershell
   cd backend
   npm install
   npx prisma migrate deploy --config prisma7.config.ts
   npx prisma generate --config prisma7.config.ts
   npm test
   npm run dev
   ```
3. Phone with USB: `adb reverse tcp:5000 tcp:5000`, Android Studio -> Sync Project -> Run.
   Your data is kept (database upgrade 3 -> 4).
4. Try it: Profile -> Daily routine. Set Travel to 30 / 30, turn on Rest (e.g. 16:00, 30 minutes),
   Save, then look at a day with classes on Home.
5. When that looks right, follow DEPLOY.md.

## If something goes wrong
- Crash on launch mentioning "Migration": uninstall the app, run again. Your data is on the server.
- Settings won't save: the server must be the new version (step 2). The app still works offline meanwhile.
