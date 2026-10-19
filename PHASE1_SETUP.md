# Phase 1 setup: login, sync, tasks (Android app + upgraded backend)

What changed
- backend/: cookie sessions + Passport replaced by Google ID-token login that returns a JWT
  access token and a rotating refresh token. Tasks API now supports offline sync
  (client-chosen ids, soft deletes, `?since=` incremental pull). Removed the unauthenticated
  `/api/users` route. Added helmet, rate limiting, validation (zod) and tests.
- android/: new Kotlin + Jetpack Compose app. Offline-first (Room), background sync
  (WorkManager), Google sign-in (Credential Manager).
- frontend/: the old React web app. It relied on cookie sessions, so it no longer works with
  the new backend. Keep it for reference or delete it.

## 1. Google Cloud Console (one-time)
1. APIs & Services -> OAuth consent screen: set it up, keep it in "Testing" and add your own
   Google account(s) under "Test users".
2. Credentials -> Create credentials -> OAuth client ID -> **Web application**.
   Copy the client ID. This is `GOOGLE_WEB_CLIENT_ID` (backend) and `planner.googleWebClientId` (app).
   No redirect URI is needed any more.
3. Credentials -> Create credentials -> OAuth client ID -> **Android**.
   - Package name: `com.smartstudent.planner`
   - SHA-1 of your debug keystore:
     `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`
     (on Windows the keystore is in `%USERPROFILE%\.android\debug.keystore`)
   You never paste this client's ID anywhere; Google matches it by package name + SHA-1.
4. Because the old client secret was shared in a zip, create a fresh Web client and delete the old one.

## 2. Backend
```bash
cd backend
npm install
cp .env.example .env        # then fill in DATABASE_URL, GOOGLE_WEB_CLIENT_ID, JWT_SECRET
npx prisma migrate deploy --config prisma7.config.ts
npx prisma generate --config prisma7.config.ts
npm test                    # 8 tests, no database needed
npm run dev
```
Generate a JWT secret: `node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"`

To use two devices you need a backend both can reach. Options:
- Same Wi-Fi: run it on your PC and use your PC's LAN IP (allow port 5000 in the firewall).
- Deploy: Render/Railway for the API + Neon/Supabase for Postgres (recommended; set the same
  env vars there, run `npx prisma migrate deploy` once, start command `npm start`).
  Then use the https URL in the app.

## 3. Android app
1. Open the `android/` folder in Android Studio (Koala or newer, JDK 17). Let Gradle sync.
   If it suggests newer library versions, accepting the stable ones is fine.
2. Copy `local.properties.example` to `local.properties` and set:
   - `planner.baseUrl` (must end with `/`):
     emulator -> `http://10.0.2.2:5000/api/`, real phone -> `http://<PC-LAN-IP>:5000/api/`,
     deployed -> `https://<your-host>/api/`
   - `planner.googleWebClientId`
3. Use an emulator with Google Play services (a "Google Play" system image) signed in to a
   Google account, or a real phone. Run the app.

## 4. Check that phase 1 works
1. Sign in, add 2-3 tasks, tick one, delete one.
2. Turn on airplane mode, add a task. It shows "not synced". Turn airplane mode off. It syncs.
3. Install on a second phone (or emulator), sign in with the same Google account. The same
   tasks appear. Change something on one device, then bring the other to the foreground.
4. Sign out and sign in with a different Google account. You should see an empty list.

## Troubleshooting
- "No credentials available" / sign-in dialog fails: package name or SHA-1 on the Android
  OAuth client doesn't match, the account isn't a test user, or the emulator has no Google account.
- "Can't reach the server": wrong `planner.baseUrl`, backend not running, or firewall.
- Server logs "Google sign-in failed": `GOOGLE_WEB_CLIENT_ID` on the backend differs from the one in the app.
- Backend exits with "Missing environment variables": fill `.env`.

## Known limits (planned for later phases)
- Conflict rule is simple: the latest write wins, and local unsynced edits win over remote ones.
- Tokens are stored unencrypted in app-private storage; wrap them with Android Keystore before a public release.
- Release builds need an https backend (plain http is only allowed in debug builds).
- Recurring-task fields exist in the database but aren't used by the app yet (phase 3).
