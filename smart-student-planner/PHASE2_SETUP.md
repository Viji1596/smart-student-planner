# Phase 2: daily routine, timetable, free-time calculation

## What's new
Backend
- `UserSettings` table + `GET/PUT /api/settings` (wake time, bedtime, morning routine, 3 meals).
- Timetable is now a proper synced resource: `GET /api/timetable?since=`, `PUT/DELETE /api/timetable/:id`.
  Days are ISO weekdays: 1 = Monday ... 7 = Sunday. Times are "HH:mm".
- New migration: `20261019000000_timetable_sync_and_settings`.
- 17 backend tests (`npm test`).

Android app
- Bottom navigation: Home, Tasks, Timetable, Settings.
- Settings tab: wake-up, bedtime (after midnight is fine), morning routine, breakfast/lunch/dinner.
- Timetable tab: add classes for several weekdays at once, edit, delete.
- Home tab: free time for any day, a timeline (routine, meals, classes, free slots),
  warnings when things overlap, open/overdue task counts.
- Everything works offline and syncs like tasks do. Sign out moved to Settings.
- The free-time logic lives in `domain/DayPlanner.kt` (pure Kotlin) with 12 unit tests
  in `app/src/test`.

## Update steps
1. Copy this folder's files over your project (replace when asked). Your `backend/.env`
   and `android/local.properties` are not in the zip, so they stay as they are.
2. Backend (VPN off):
   ```powershell
   cd backend
   npx prisma migrate deploy --config prisma7.config.ts
   npx prisma generate --config prisma7.config.ts
   npm test
   npm run dev
   ```
   No new npm packages are needed.
3. Phone: with USB connected, run `adb reverse tcp:5000 tcp:5000` again if needed,
   then in Android Studio click **Sync Project** and **Run**.
   Your existing tasks are kept (the local database is upgraded, not wiped).

## Try it
1. Settings tab: set your wake time, bedtime and meal times, tap Save.
2. Timetable tab: tap +, add "Maths" 09:00-10:30 and tick Mon, Wed, Fri.
3. Home tab: pick Monday. You should see the class in the timeline and the free time reduced.
4. Add a class that overlaps lunch. Home shows an "Overlapping items" warning.
5. Sign in on a second device: settings and timetable appear there too.

## Run the unit tests in Android Studio
Open `app/src/test/java/.../domain/DayPlannerTest.kt`, click the green arrow beside the class name.

## If something goes wrong
- App crashes on launch with a Room "Migration didn't properly handle" error: uninstall the
  app from the phone and run again. Your data is safe on the server and syncs back after sign-in.
- `prisma migrate deploy` can't reach the database: turn the VPN off; wake the Neon branch.
- Time picker looks cut off on a small screen: tell me the phone model and I'll adjust it.
