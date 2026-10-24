const test = require("node:test");
const assert = require("node:assert/strict");

process.env.JWT_SECRET = "test-secret-test-secret-123";
process.env.GOOGLE_WEB_CLIENT_ID = "test-client";

// --- In-memory fake of the Prisma calls these routes use --------------------
const entries = new Map();
const settingsByUser = new Map();
let clock = 1000;
const tick = () => new Date(Date.UTC(2026, 0, 1) + clock++ * 1000);

const fakePrisma = {
  timetableEntry: {
    findUnique: async ({ where }) => entries.get(where.id) ?? null,
    findMany: async ({ where }) =>
      [...entries.values()].filter(
        (e) =>
          e.userId === where.userId &&
          (where.deletedAt === null ? e.deletedAt === null : true) &&
          (!where.updatedAt || e.updatedAt >= where.updatedAt.gte)
      ),
    upsert: async ({ where, create, update }) => {
      const existing = entries.get(where.id);
      const row = existing
        ? { ...existing, ...update, updatedAt: tick() }
        : { deletedAt: null, ...create, updatedAt: tick() };
      entries.set(where.id, row);
      return row;
    },
    updateMany: async ({ where, data }) => {
      const e = entries.get(where.id);
      if (!e || e.userId !== where.userId) return { count: 0 };
      entries.set(where.id, { ...e, ...data, updatedAt: tick() });
      return { count: 1 };
    },
  },
  userSettings: {
    findUnique: async ({ where }) => settingsByUser.get(where.userId) ?? null,
    upsert: async ({ where, create, update }) => {
      const existing = settingsByUser.get(where.userId);
      const row = existing
        ? { ...existing, ...update, updatedAt: tick() }
        : { ...create, updatedAt: tick() };
      settingsByUser.set(where.userId, row);
      return row;
    },
  },
};

const path = require("node:path");
const prismaPath = require.resolve("../lib/prisma");
require.cache[prismaPath] = { id: prismaPath, filename: prismaPath, loaded: true, exports: fakePrisma };

const app = require("../app");
const { signAccessToken } = require("../lib/tokens");

let server, base;
test.before(async () => {
  await new Promise((resolve) => (server = app.listen(0, resolve)));
  base = `http://127.0.0.1:${server.address().port}`;
});
test.after(() => server.close());

const headers = (userId) => ({
  Authorization: `Bearer ${signAccessToken(userId)}`,
  "Content-Type": "application/json",
});

const lecture = { dayOfWeek: 1, title: "Maths", startTime: "09:00", endTime: "10:30" };

const validSettings = {
  wakeTime: "06:30",
  sleepTime: "23:00",
  morningRoutineMinutes: 45,
  breakfastStart: "08:00",
  breakfastMinutes: 20,
  lunchStart: "13:00",
  lunchMinutes: 40,
  dinnerStart: "20:00",
  dinnerMinutes: 40,
  timezone: "Asia/Kolkata",
};

// ---------------------------------------------------------------- timetable
test("timetable requires auth", async () => {
  assert.equal((await fetch(`${base}/api/timetable`)).status, 401);
  assert.equal((await fetch(`${base}/api/settings`)).status, 401);
});

test("timetable: create, update, list", async () => {
  const id = "entry_abc12345";
  let res = await fetch(`${base}/api/timetable/${id}`, {
    method: "PUT", headers: headers("u1"), body: JSON.stringify(lecture),
  });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).startTime, "09:00");

  res = await fetch(`${base}/api/timetable/${id}`, {
    method: "PUT", headers: headers("u1"), body: JSON.stringify({ ...lecture, title: "Maths II" }),
  });
  assert.equal((await res.json()).title, "Maths II");

  res = await fetch(`${base}/api/timetable`, { headers: headers("u1") });
  const body = await res.json();
  assert.equal(body.entries.length, 1);
  assert.ok(body.serverTime);
});

test("timetable: validation", async () => {
  for (const bad of [
    { ...lecture, dayOfWeek: 0 },
    { ...lecture, dayOfWeek: 8 },
    { ...lecture, title: "  " },
    { ...lecture, startTime: "9:00" },
    { ...lecture, endTime: "25:00" },
    { ...lecture, startTime: "11:00", endTime: "10:00" },
    { ...lecture, startTime: "10:00", endTime: "10:00" },
  ]) {
    const res = await fetch(`${base}/api/timetable/entry_bad12345`, {
      method: "PUT", headers: headers("u1"), body: JSON.stringify(bad),
    });
    assert.equal(res.status, 400, JSON.stringify(bad));
  }
});

test("timetable: other users are isolated", async () => {
  const id = "entry_priv1234";
  await fetch(`${base}/api/timetable/${id}`, { method: "PUT", headers: headers("owner"), body: JSON.stringify(lecture) });

  let res = await fetch(`${base}/api/timetable/${id}`, {
    method: "PUT", headers: headers("intruder"), body: JSON.stringify({ ...lecture, title: "hacked" }),
  });
  assert.equal(res.status, 404);

  res = await fetch(`${base}/api/timetable`, { headers: headers("intruder") });
  assert.equal((await res.json()).entries.length, 0);

  res = await fetch(`${base}/api/timetable/${id}`, { method: "DELETE", headers: headers("intruder") });
  assert.equal((await res.json()).deleted, 0);
});

test("timetable: deletions show up in incremental sync", async () => {
  const id = "entry_del12345";
  await fetch(`${base}/api/timetable/${id}`, { method: "PUT", headers: headers("u2"), body: JSON.stringify(lecture) });
  const since = new Date(Date.UTC(2026, 0, 1)).toISOString();
  await fetch(`${base}/api/timetable/${id}`, { method: "DELETE", headers: headers("u2") });

  let res = await fetch(`${base}/api/timetable`, { headers: headers("u2") });
  assert.equal((await res.json()).entries.length, 0);

  res = await fetch(`${base}/api/timetable?since=${since}`, { headers: headers("u2") });
  const body = await res.json();
  assert.equal(body.entries.length, 1);
  assert.equal(body.entries[0].deleted, true);
});

// ----------------------------------------------------------------- settings
test("settings: null until saved, then returned", async () => {
  let res = await fetch(`${base}/api/settings`, { headers: headers("s1") });
  assert.equal((await res.json()).settings, null);

  res = await fetch(`${base}/api/settings`, {
    method: "PUT", headers: headers("s1"), body: JSON.stringify(validSettings),
  });
  assert.equal(res.status, 200);

  res = await fetch(`${base}/api/settings`, { headers: headers("s1") });
  assert.equal((await res.json()).settings.wakeTime, "06:30");

  // update
  res = await fetch(`${base}/api/settings`, {
    method: "PUT", headers: headers("s1"), body: JSON.stringify({ ...validSettings, wakeTime: "05:45" }),
  });
  assert.equal((await res.json()).wakeTime, "05:45");
});

test("settings: sleeping after midnight is allowed", async () => {
  const res = await fetch(`${base}/api/settings`, {
    method: "PUT", headers: headers("s2"), body: JSON.stringify({ ...validSettings, sleepTime: "00:30" }),
  });
  assert.equal(res.status, 200);
});

test("settings: validation", async () => {
  for (const bad of [
    { ...validSettings, wakeTime: "6:30" },
    { ...validSettings, sleepTime: "06:30" }, // same as wake
    { ...validSettings, morningRoutineMinutes: -1 },
    { ...validSettings, morningRoutineMinutes: 500 },
    { ...validSettings, lunchMinutes: 1 },
    { ...validSettings, timezone: "Mars/Olympus" },
  ]) {
    const res = await fetch(`${base}/api/settings`, {
      method: "PUT", headers: headers("s3"), body: JSON.stringify(bad),
    });
    assert.equal(res.status, 400, JSON.stringify(bad));
  }
});

test("settings: users cannot see each other's settings", async () => {
  await fetch(`${base}/api/settings`, { method: "PUT", headers: headers("a"), body: JSON.stringify(validSettings) });
  const res = await fetch(`${base}/api/settings`, { headers: headers("b") });
  assert.equal((await res.json()).settings, null);
});

// ------------------------------------------------- phase 3c: travel, rest, breaks
const fullSettings = {
  ...validSettings,
  commuteToMinutes: 30,
  commuteFromMinutes: 45,
  arriveEarlyMinutes: 10,
  restStart: "16:30",
  restMinutes: 30,
  breakMinutes: 5,
  longBreakAfterMinutes: 90,
  longBreakMinutes: 25,
};

test("settings: travel, rest and break options are saved and returned", async () => {
  let res = await fetch(`${base}/api/settings`, {
    method: "PUT", headers: headers("n1"), body: JSON.stringify(fullSettings),
  });
  assert.equal(res.status, 200);
  const saved = await res.json();
  for (const key of Object.keys(fullSettings)) assert.equal(saved[key], fullSettings[key], key);

  res = await fetch(`${base}/api/settings`, { headers: headers("n1") });
  assert.equal((await res.json()).settings.commuteFromMinutes, 45);
});

test("settings: an older app that doesn't send the new fields gets sensible defaults", async () => {
  const res = await fetch(`${base}/api/settings`, {
    method: "PUT", headers: headers("n2"), body: JSON.stringify(validSettings),
  });
  assert.equal(res.status, 200);
  const saved = await res.json();
  assert.equal(saved.commuteToMinutes, 0);
  assert.equal(saved.commuteFromMinutes, 0);
  assert.equal(saved.arriveEarlyMinutes, 10);
  assert.equal(saved.restMinutes, 0);
  assert.equal(saved.restStart, "16:00");
  assert.equal(saved.breakMinutes, 10);
  assert.equal(saved.longBreakAfterMinutes, 120);
  assert.equal(saved.longBreakMinutes, 20);
});

test("settings: zero turns each option off", async () => {
  const res = await fetch(`${base}/api/settings`, {
    method: "PUT", headers: headers("n3"),
    body: JSON.stringify({ ...fullSettings, commuteToMinutes: 0, commuteFromMinutes: 0, restMinutes: 0, breakMinutes: 0, longBreakAfterMinutes: 0 }),
  });
  assert.equal(res.status, 200);
});

test("settings: new fields are validated", async () => {
  for (const bad of [
    { ...fullSettings, commuteToMinutes: -1 },
    { ...fullSettings, commuteToMinutes: 241 },
    { ...fullSettings, commuteFromMinutes: 999 },
    { ...fullSettings, arriveEarlyMinutes: 61 },
    { ...fullSettings, restStart: "4pm" },
    { ...fullSettings, restMinutes: 181 },
    { ...fullSettings, breakMinutes: 61 },
    { ...fullSettings, longBreakAfterMinutes: 481 },
    { ...fullSettings, longBreakMinutes: 121 },
  ]) {
    const res = await fetch(`${base}/api/settings`, {
      method: "PUT", headers: headers("n4"), body: JSON.stringify(bad),
    });
    assert.equal(res.status, 400, JSON.stringify(bad));
  }
});

test("requests arriving through a proxy (as on Render) are accepted", async () => {
  // Render puts the visitor's address in X-Forwarded-For. The server must trust one proxy,
  // otherwise the login rate limiter treats everybody as one person.
  const res = await fetch(`${base}/api/auth/google`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Forwarded-For": "203.0.113.7" },
    body: JSON.stringify({}),
  });
  assert.equal(res.status, 400); // rejected for the empty body, not for the proxy header
  const health = await fetch(`${base}/health`, { headers: { "X-Forwarded-For": "203.0.113.7" } });
  assert.equal(health.status, 200);
});
