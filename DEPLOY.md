# Deploying the backend (so the app works without your PC or USB)

Plan: GitHub (your code) -> Render (runs the Node server) -> Neon (your database, unchanged).
Everything here is free-tier. Free limits change, so check Render's dashboard when you sign up.

## 0. What you need
- A GitHub account and a Render account (sign in to Render with GitHub).
- Your Neon connection string. Use the DIRECT one (Neon -> Connect -> turn "Connection pooling" off,
  so the host has no `-pooler`), ending in `?sslmode=require`.
- Your Google **Web** client ID (the same one in backend/.env).

## 1. Put the project on GitHub (private repo recommended)
Open PowerShell in the project folder (the one that contains `android` and `backend`).

```powershell
git rev-parse --show-toplevel      # should print this project folder. If it prints only backend/, tell me.
git status
```

Check that secrets are NOT tracked. This must print nothing except the two `.example` files:
```powershell
git ls-files | findstr /i /c:".env" /c:"local.properties" /c:"node_modules"
```
If it lists `backend/.env` or `android/local.properties`, stop: run
`git rm --cached backend/.env android/local.properties` and make sure `.env` and `local.properties`
are in a `.gitignore` before you commit.

Then:
```powershell
git add .
git commit -m "Smart Student Planner"
```
Create an empty private repository on github.com (no README), then run the two commands GitHub shows
(`git remote add origin ...` and `git push -u origin main`).

## 2. Create the server on Render
1. Render dashboard -> **New** -> **Blueprint** -> pick your repository.
   Render reads `render.yaml` and shows one service: `smart-student-planner-api` on the **Free** plan.
2. It asks for two values (the third, JWT_SECRET, is generated for you):
   - `DATABASE_URL` = your Neon direct connection string
   - `GOOGLE_WEB_CLIENT_ID` = your Web client ID
3. Click **Apply**. The first build takes a few minutes. The build also applies your database migrations.
4. When it says Live, open `https://<your-service>.onrender.com/health` in a browser.
   You should see `{"ok":true}`. (Use the exact address Render shows; it may have a suffix.)

No Blueprint? Use New -> Web Service instead and enter: Root directory `backend`, Build command
`npm install && npx prisma generate --config prisma7.config.ts && npx prisma migrate deploy --config prisma7.config.ts`,
Start command `npm start`, Health check path `/health`, Instance type Free, and add the three environment
variables (JWT_SECRET: any long random string; make one with
`node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"`).

From now on, every `git push` redeploys the server automatically.

## 3. Point the app at it and build the APK
1. Edit `android/local.properties`:
   ```
   planner.baseUrl=https://<your-service>.onrender.com/api/
   ```
   (https, and it must end with `/api/`). Keep your `planner.googleWebClientId` line.
2. Android Studio: **File -> Sync Project with Gradle Files**.
3. **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**. When it finishes, click **locate**.
   The file is `android/app/build/outputs/apk/debug/app-debug.apk`.
4. Install it on your phone, replacing the old one (your data is kept):
   - With the cable one last time: `adb install -r app-debug.apk` (from the folder with the file), or just press Run.
   - Or copy the APK to the phone and open it (allow "Install unknown apps" for your file manager).
5. Unplug the phone. Open the app.

The first open after a quiet period shows "Waking up the server..." for up to a minute. That is the free
server (and then the free database) waking up. Later opens are quick.

## 4. Check it really works without the cable
1. Unplug, turn Wi-Fi/mobile data on, open the app, sign in, add a task.
2. Open the app on a second device (or reinstall and sign in): the task appears.
3. Airplane mode on, add a task (shows "not synced"), airplane mode off: it syncs.
4. Leave the app alone for 20 minutes, open it again: you should see the "Waking up" message once.

## 5. Sharing with friends
- The same APK works on their phones (the Google sign-in is matched by the signing key, which is yours).
- Your Google consent screen is in testing mode, so add each friend's Google email under
  Google Auth Platform -> **Audience** -> Test users. Without this, their sign-in is refused.

## 6. If something is wrong
- Render dashboard -> your service -> **Logs**. Build failures and crashes show up there.
- Build fails at `prisma migrate deploy`: the DATABASE_URL is wrong or Neon is asleep. Fix the value in
  Environment, wake Neon (SQL editor -> `SELECT 1;`), then **Manual Deploy**.
- `/health` works but the app says it can't reach the server: check `planner.baseUrl` (https, ends with `/api/`),
  rebuild and reinstall.
- Sign-in says the server rejected it: `GOOGLE_WEB_CLIENT_ID` on Render must equal the one in `local.properties`.
- Everyone gets "too many requests" (HTTP 429): the login endpoints allow 60 tries per 15 minutes per person.
  If many people share one Wi-Fi this can trigger; wait 15 minutes, or tell me and I'll raise the limit.

## Good to know
- Free Render servers sleep after 15 idle minutes and wake in about a minute (as of the docs I checked in
  October 2026). A free "uptime" pinger can keep one awake, but it uses your free hours.
- Do NOT use Render's own free database: Render's free databases expire. Keep using Neon.
- Never commit `backend/.env`, `android/local.properties` or your Google client secret.
