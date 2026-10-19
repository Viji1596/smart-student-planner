const test = require("node:test");
const assert = require("node:assert/strict");

process.env.JWT_SECRET = "test-secret-test-secret-123";
process.env.GOOGLE_WEB_CLIENT_ID = "test-client";

// --- In-memory fake of the Prisma calls these routes use --------------------
const habits = new Map();
const completions = new Map();
const tasks = new Map();
let clock = 1000;
const tick = () => new Date(Date.UTC(2026, 0, 1) + clock++ * 1000);

function crud(store) {
  return {
    findUnique: async ({ where }) => store.get(where.id) ?? null,
    findMany: async ({ where }) =>
      [...store.values()].filter(
        (r) =>
          r.userId === where.userId &&
          (where.deletedAt === null ? r.deletedAt === null : true) &&
          (!where.updatedAt || r.updatedAt >= where.updatedAt.gte)
      ),
    upsert: async ({ where, create, update }) => {
      const existing = store.get(where.id);
      const row = existing
        ? { ...existing, ...update, updatedAt: tick() }
        : { deletedAt: null, ...create, updatedAt: tick() };
      store.set(where.id, row);
      return row;
    },
    updateMany: async ({ where, data }) => {
      const r = store.get(where.id);
      if (!r || r.userId !== where.userId) return { count: 0 };
      store.set(where.id, { ...r, ...data, updatedAt: tick() });
      return { count: 1 };
    },
  };
}

const fakePrisma = { habit: crud(habits), habitCompletion: crud(completions), task: crud(tasks) };
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

const headers = (u) => ({ Authorization: `Bearer ${signAccessToken(u)}`, "Content-Type": "application/json" });
const put = (path, user, body) =>
  fetch(`${base}${path}`, { method: "PUT", headers: headers(user), body: JSON.stringify(body) });

const daily = { title: "Coding practice", minutes: 45, priority: "Medium", kind: "DAILY" };
const weekly = { title: "Aptitude", minutes: 60, priority: "High", kind: "WEEKLY", timesPerWeek: 2 };
const onDays = { title: "Gym", minutes: 60, priority: "Low", kind: "DAYS", days: "1,3,5" };

// ------------------------------------------------------------------- habits
test("habits require auth", async () => {
  assert.equal((await fetch(`${base}/api/habits`)).status, 401);
  assert.equal((await fetch(`${base}/api/habit-completions`)).status, 401);
});

test("habit: create the three kinds and list them", async () => {
  for (const [id, body] of [["habit_daily1", daily], ["habit_week01", weekly], ["habit_days01", onDays]]) {
    const res = await put(`/api/habits/${id}`, "u1", body);
    assert.equal(res.status, 200, JSON.stringify(await res.clone().json()));
  }
  const list = await (await fetch(`${base}/api/habits`, { headers: headers("u1") })).json();
  assert.equal(list.habits.length, 3);
  const w = list.habits.find((h) => h.id === "habit_week01");
  assert.equal(w.timesPerWeek, 2);
  assert.equal(w.days, "");
  assert.equal(list.habits.find((h) => h.id === "habit_days01").days, "1,3,5");
});

test("habit: stray fields are cleaned for the kind", async () => {
  const res = await put("/api/habits/habit_clean01", "u1", { ...daily, days: "1,2", timesPerWeek: 5 });
  const body = await res.json();
  assert.equal(body.days, "");
  assert.equal(body.timesPerWeek, 0);
});

test("habit: validation", async () => {
  for (const bad of [
    { ...daily, title: "" },
    { ...daily, minutes: 2 },
    { ...daily, minutes: 500 },
    { ...daily, priority: "Urgent" },
    { ...daily, kind: "MONTHLY" },
    { ...onDays, days: "" },
    { ...onDays, days: "1,1" },
    { ...onDays, days: "0,8" },
    { ...weekly, timesPerWeek: 0 },
    { ...weekly, timesPerWeek: 8 },
  ]) {
    const res = await put("/api/habits/habit_bad0001", "u1", bad);
    assert.equal(res.status, 400, JSON.stringify(bad));
  }
});

test("habit: other users cannot touch it", async () => {
  await put("/api/habits/habit_priv001", "owner", daily);
  let res = await put("/api/habits/habit_priv001", "intruder", { ...daily, title: "hacked" });
  assert.equal(res.status, 404);
  res = await fetch(`${base}/api/habits`, { headers: headers("intruder") });
  assert.equal((await res.json()).habits.length, 0);
  res = await fetch(`${base}/api/habits/habit_priv001`, { method: "DELETE", headers: headers("intruder") });
  assert.equal((await res.json()).deleted, 0);
});

test("habit: delete is visible through ?since", async () => {
  await put("/api/habits/habit_del0001", "u2", daily);
  const since = new Date(Date.UTC(2026, 0, 1)).toISOString();
  await fetch(`${base}/api/habits/habit_del0001`, { method: "DELETE", headers: headers("u2") });
  let res = await (await fetch(`${base}/api/habits`, { headers: headers("u2") })).json();
  assert.equal(res.habits.length, 0);
  res = await (await fetch(`${base}/api/habits?since=${since}`, { headers: headers("u2") })).json();
  assert.equal(res.habits[0].deleted, true);
});

// -------------------------------------------------------------- completions
test("completion: mark done, undo, redo", async () => {
  await put("/api/habits/habit_comp001", "c1", daily);
  const id = "habit_comp001_2026-10-19";
  const body = { habitId: "habit_comp001", date: "2026-10-19" };

  let res = await put(`/api/habit-completions/${id}`, "c1", body);
  assert.equal(res.status, 200);
  let list = await (await fetch(`${base}/api/habit-completions`, { headers: headers("c1") })).json();
  assert.equal(list.completions.length, 1);

  // marking it again from another device is harmless
  res = await put(`/api/habit-completions/${id}`, "c1", body);
  assert.equal(res.status, 200);
  list = await (await fetch(`${base}/api/habit-completions`, { headers: headers("c1") })).json();
  assert.equal(list.completions.length, 1);

  // undo
  await fetch(`${base}/api/habit-completions/${id}`, { method: "DELETE", headers: headers("c1") });
  list = await (await fetch(`${base}/api/habit-completions`, { headers: headers("c1") })).json();
  assert.equal(list.completions.length, 0);

  // redo brings it back
  await put(`/api/habit-completions/${id}`, "c1", body);
  list = await (await fetch(`${base}/api/habit-completions`, { headers: headers("c1") })).json();
  assert.equal(list.completions.length, 1);
});

test("completion: id must match habit and date", async () => {
  await put("/api/habits/habit_comp002", "c2", daily);
  const res = await put("/api/habit-completions/some_other_id_x", "c2", { habitId: "habit_comp002", date: "2026-10-19" });
  assert.equal(res.status, 400);
});

test("completion: bad dates are rejected", async () => {
  await put("/api/habits/habit_comp003", "c3", daily);
  for (const date of ["2026-13-01", "2026-02-30", "19-10-2026", "2026-10-19T00:00:00Z"]) {
    const res = await put(`/api/habit-completions/habit_comp003_${date}`, "c3", { habitId: "habit_comp003", date });
    assert.equal(res.status, 400, date);
  }
});

test("completion: cannot complete someone else's habit", async () => {
  await put("/api/habits/habit_comp004", "owner2", daily);
  const res = await put("/api/habit-completions/habit_comp004_2026-10-19", "intruder2", {
    habitId: "habit_comp004", date: "2026-10-19",
  });
  assert.equal(res.status, 404);
  const none = await (await fetch(`${base}/api/habit-completions`, { headers: headers("intruder2") })).json();
  assert.equal(none.completions.length, 0);
});

test("completion: unknown habit is a 404", async () => {
  const res = await put("/api/habit-completions/nohabit_here_2026-10-19", "c4", { habitId: "nohabit_here", date: "2026-10-19" });
  assert.equal(res.status, 404);
});

// ------------------------------------------------------------- task progress
test("task: spentMinutes is stored and capped at the estimate", async () => {
  const task = { title: "Assignment", priority: "High", estimatedMinutes: 120, spentMinutes: 45 };
  let res = await put("/api/tasks/task_prog0001", "t1", task);
  assert.equal((await res.json()).spentMinutes, 45);
  res = await put("/api/tasks/task_prog0001", "t1", { ...task, spentMinutes: 999 });
  assert.equal((await res.json()).spentMinutes, 120);
  res = await put("/api/tasks/task_prog0002", "t1", { title: "No progress sent", priority: "Low", estimatedMinutes: 30 });
  assert.equal((await res.json()).spentMinutes, 0);
  res = await put("/api/tasks/task_prog0003", "t1", { ...task, spentMinutes: -5 });
  assert.equal(res.status, 400);
});
