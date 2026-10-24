const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

process.env.JWT_SECRET = "test-secret-test-secret-123";
process.env.GOOGLE_WEB_CLIENT_ID = "test-client";

// --- In-memory fake of the Prisma calls our routes use ---------------------
const tasks = new Map();
let clock = 1000;
const tick = () => new Date(Date.UTC(2026, 0, 1) + clock++ * 1000);

const fakePrisma = {
  task: {
    findUnique: async ({ where }) => tasks.get(where.id) ?? null,
    findMany: async ({ where }) =>
      [...tasks.values()].filter(
        (t) =>
          t.userId === where.userId &&
          (where.deletedAt === null ? t.deletedAt === null : true) &&
          (!where.updatedAt || t.updatedAt >= where.updatedAt.gte)
      ),
    upsert: async ({ where, create, update }) => {
      const existing = tasks.get(where.id);
      const row = existing
        ? { ...existing, ...update, updatedAt: tick() }
        : { deletedAt: null, ...create, updatedAt: tick() };
      tasks.set(where.id, row);
      return row;
    },
    updateMany: async ({ where, data }) => {
      const t = tasks.get(where.id);
      if (!t || t.userId !== where.userId) return { count: 0 };
      tasks.set(where.id, { ...t, ...data, updatedAt: tick() });
      return { count: 1 };
    },
  },
};

const prismaPath = require.resolve("../lib/prisma");
require.cache[prismaPath] = {
  id: prismaPath,
  filename: prismaPath,
  loaded: true,
  exports: fakePrisma,
};

const app = require("../app");
const { signAccessToken } = require("../lib/tokens");

let server, base;
test.before(async () => {
  await new Promise((resolve) => {
    server = app.listen(0, resolve);
  });
  base = `http://127.0.0.1:${server.address().port}`;
});
test.after(() => server.close());

const authHeader = (userId) => ({
  Authorization: `Bearer ${signAccessToken(userId)}`,
  "Content-Type": "application/json",
});

const validTask = {
  title: "Solve aptitude set",
  priority: "High",
  estimatedMinutes: 45,
  dueDate: "2026-10-20T10:00:00.000Z",
};

test("health works without auth", async () => {
  const res = await fetch(`${base}/health`);
  assert.equal(res.status, 200);
});

test("tasks require a token", async () => {
  const res = await fetch(`${base}/api/tasks`);
  assert.equal(res.status, 401);
});

test("garbage token is rejected", async () => {
  const res = await fetch(`${base}/api/tasks`, {
    headers: { Authorization: "Bearer not-a-real-token" },
  });
  assert.equal(res.status, 401);
});

test("old users endpoint is gone", async () => {
  const res = await fetch(`${base}/api/users`);
  assert.equal(res.status, 404);
});

test("client-chosen id: create, read, update", async () => {
  const id = "task_abc12345";
  let res = await fetch(`${base}/api/tasks/${id}`, {
    method: "PUT",
    headers: authHeader("u1"),
    body: JSON.stringify(validTask),
  });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).id, id);

  res = await fetch(`${base}/api/tasks/${id}`, {
    method: "PUT",
    headers: authHeader("u1"),
    body: JSON.stringify({ ...validTask, completed: true }),
  });
  assert.equal((await res.json()).completed, true);

  res = await fetch(`${base}/api/tasks`, { headers: authHeader("u1") });
  const body = await res.json();
  assert.equal(body.tasks.length, 1);
  assert.ok(body.serverTime);
});

test("validation rejects bad input", async () => {
  for (const bad of [
    { ...validTask, title: "" },
    { ...validTask, priority: "Urgent" },
    { ...validTask, estimatedMinutes: 0 },
    { ...validTask, dueDate: "tomorrow" },
  ]) {
    const res = await fetch(`${base}/api/tasks/task_bad12345`, {
      method: "PUT",
      headers: authHeader("u1"),
      body: JSON.stringify(bad),
    });
    assert.equal(res.status, 400, JSON.stringify(bad));
  }
});

test("another user cannot touch or see my task", async () => {
  const id = "task_priv1234";
  await fetch(`${base}/api/tasks/${id}`, {
    method: "PUT",
    headers: authHeader("owner"),
    body: JSON.stringify(validTask),
  });

  let res = await fetch(`${base}/api/tasks/${id}`, {
    method: "PUT",
    headers: authHeader("intruder"),
    body: JSON.stringify({ ...validTask, title: "hacked" }),
  });
  assert.equal(res.status, 404);

  res = await fetch(`${base}/api/tasks`, { headers: authHeader("intruder") });
  assert.equal((await res.json()).tasks.length, 0);

  res = await fetch(`${base}/api/tasks/${id}`, {
    method: "DELETE",
    headers: authHeader("intruder"),
  });
  assert.equal((await res.json()).deleted, 0);
});

test("deletions are visible through ?since", async () => {
  const id = "task_del12345";
  await fetch(`${base}/api/tasks/${id}`, {
    method: "PUT",
    headers: authHeader("u2"),
    body: JSON.stringify(validTask),
  });
  const since = new Date(Date.UTC(2026, 0, 1)).toISOString();

  await fetch(`${base}/api/tasks/${id}`, {
    method: "DELETE",
    headers: authHeader("u2"),
  });

  // Plain list hides it
  let res = await fetch(`${base}/api/tasks`, { headers: authHeader("u2") });
  assert.equal((await res.json()).tasks.length, 0);

  // Incremental sync reports it as deleted
  res = await fetch(`${base}/api/tasks?since=${since}`, {
    headers: authHeader("u2"),
  });
  const body = await res.json();
  assert.equal(body.tasks.length, 1);
  assert.equal(body.tasks[0].deleted, true);
});
