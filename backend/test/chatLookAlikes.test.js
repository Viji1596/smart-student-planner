const test = require("node:test");
const assert = require("node:assert/strict");
const express = require("express");

process.env.JWT_SECRET = "test-secret-test-secret-123";
const { interpret } = require("../lib/chat/rules");
const { validateAction } = require("../lib/chat/actions");
const { buildRouter } = require("../routes/chatRoutes");
const { signAccessToken } = require("../lib/tokens");

// two tasks with exactly the same name (like the screenshot), a third that only contains it, and a different one
const base = {
  today: "2026-10-24",
  nowMin: 19 * 60,
  tasks: [
    { id: "a", title: "Deep learning project", minutes: 60, due: "2026-10-27", completed: false },
    { id: "b", title: "Deep learning project", minutes: 90, due: "2026-10-30", completed: false },
    { id: "c", title: "Physics lab", minutes: 60, completed: false },
  ],
  habits: [],
  plan: {
    sessions: [
      { id: "sa", title: "Deep learning project", kind: "TASK", refId: "a", start: "20:00", end: "21:00", status: "PLANNED" },
      { id: "sb", title: "Deep learning project", kind: "TASK", refId: "b", start: "21:20", end: "22:20", status: "PLANNED" },
      { id: "sc", title: "Physics lab", kind: "TASK", refId: "c", start: "22:30", end: "23:00", status: "PLANNED" },
    ],
    busy: [], freeBlocks: [],
  },
  tomorrow: { sessions: [], freeMinutes: 400 },
};
const run = (t, h = [], ctx = base) => interpret(t, ctx, h);
const question = (t) => run(t);
const turn = (history, user, bot) => [...history, { role: "user", text: user }, { role: "assistant", text: bot }];

test("the question lists what tells them apart, and the chips carry ids (the screenshot's case)", () => {
  const r = question("move Deep learning project to tomorrow");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /You have 2 called “Deep learning project”\. Which one do you mean\?/);
  assert.match(r.reply, /1\. 20:00–21:00 · due Tue 27 Oct/);
  assert.match(r.reply, /2\. 21:20–22:20 · due Fri 30 Oct/);
  assert.deepEqual(r.clarify.options.map((o) => o.label), ["Deep learning project · 20:00–21:00 · due Tue 27 Oct", "Deep learning project · 21:20–22:20 · due Fri 30 Oct"]);
  assert.ok(r.clarify.options.every((o) => /\[\[id:\w+\]\]/.test(o.text) && /\[\[no:\w+\]\]/.test(o.text)));
});

test("with nothing planned today, the tasks are described by due date and length", () => {
  const ctx = { ...base, plan: { sessions: [], busy: [], freeBlocks: [] } };
  const r = interpret("move Deep learning project to tomorrow", ctx, []);
  assert.match(r.reply, /1\. due Tue 27 Oct · 1 h/);
  assert.match(r.reply, /2\. due Fri 30 Oct · 1 h 30 min/);
  assert.equal(interpret(r.clarify.options[1].text, ctx, []).actions[0].args.taskId, "b");
});

test("tapping a chip does exactly that one", () => {
  const r = question("move Deep learning project to tomorrow");
  const a = run(r.clarify.options[0].text);
  const b = run(r.clarify.options[1].text);
  assert.deepEqual([a.actions[0].type, a.actions[0].args.taskId], ["defer_task", "a"]);
  assert.deepEqual([b.actions[0].type, b.actions[0].args.taskId], ["defer_task", "b"]);
  assert.ok(validateAction(a.actions[0]).action);
});

test("saying 'first' / 'second' / '2' / 'the last one' works", () => {
  const r = question("move Deep learning project to tomorrow");
  const h = turn([], "move Deep learning project to tomorrow", r.reply);
  const id = (t) => run(t, h).actions[0].args.taskId;
  assert.equal(id("first"), "a");
  assert.equal(id("the first one"), "a");
  assert.equal(id("1"), "a");
  assert.equal(id("second"), "b");
  assert.equal(id("2nd"), "b");
  assert.equal(id("2"), "b");
  assert.equal(id("the last one"), "b");
  assert.equal(id("option 2"), "b");
});

test("saying what differs works: the due day, the date, the month", () => {
  const r = question("move Deep learning project to tomorrow");
  const h = turn([], "move Deep learning project to tomorrow", r.reply);
  const id = (t) => run(t, h).actions[0].args.taskId;
  assert.equal(id("the one due friday"), "b");
  assert.equal(id("friday"), "b");
  assert.equal(id("tuesday"), "a");
  assert.equal(id("30th"), "b");
  assert.equal(id("27"), "a");
  assert.equal(id("the one due on the 27th"), "a");
});

test("repeating the name no longer loops: it explains how to choose, and the sentence does not grow", () => {
  const r = question("move Deep learning project to tomorrow");
  let h = turn([], "move Deep learning project to tomorrow", r.reply);
  for (let i = 0; i < 4; i++) {
    const again = run("Deep learning project", h);
    assert.equal(again.actions.length, 0);
    assert.match(again.reply, /That doesn't tell them apart/);
    assert.ok(again.clarify.options.every((o) => o.text.length < 80), again.clarify.options[0].text);
    h = turn(h, "Deep learning project", again.reply);
  }
  // and the first/second still works after all of that
  assert.equal(run("second", h).actions[0].args.taskId, "b");
});

test("a new command in the middle of a question is a new command", () => {
  const r = question("move Deep learning project to tomorrow");
  const h = turn([], "move Deep learning project to tomorrow", r.reply);
  assert.equal(run("add task viva prep", h).actions[0].type, "add_task");
  assert.equal(run("move physics lab to 11pm", h).actions[0].args.sessionId, "sc");
});

test("moving by time, the other copy is left alone", () => {
  const r = question("move deep learning project to 11pm");
  assert.match(r.reply, /Which one do you mean\?/);
  const a = run(r.clarify.options[1].text);
  assert.deepEqual([a.actions[0].type, a.actions[0].args.sessionId, a.actions[0].args.time], ["move_session", "sb", "23:00"]);
});

test("swapping with a look-alike asks which task, then swaps the right pair of whole tasks", () => {
  const r = question("swap deep learning project with physics lab");
  assert.match(r.reply, /Which one do you mean\?/);
  assert.ok(r.clarify.options.every((o) => /\[\[id:/.test(o.text)));
  assert.match(r.clarify.options[0].label, /20:00–21:00/);
  const second = run(r.clarify.options[1].text);
  assert.deepEqual([second.actions[0].type, second.actions[0].args.taskA, second.actions[0].args.taskB], ["swap_tasks", "b", "c"]);
  // by words
  const h = turn([], "swap deep learning project with physics lab", r.reply);
  const byWord = run("the first one", h);
  assert.deepEqual([byWord.actions[0].args.taskA, byWord.actions[0].args.taskB], ["a", "c"]);
});

test("other things about a look-alike also work: done, delete (asks first), make it 2 hours", () => {
  const done = question("mark deep learning project done");
  assert.match(done.reply, /Which/);
  assert.deepEqual(run(done.clarify.options[1].text).actions[0], { type: "complete_task", args: { taskId: "b" } });
  const del = question("delete deep learning project");
  const d = run(del.clarify.options[0].text);
  assert.deepEqual([d.actions[0].type, d.actions[0].args.taskId], ["delete_task", "a"]);
  assert.match(d.reply, /confirm/i);
});

test("different names: a vague 'move physics to tomorrow' now works once you choose (it did not before)", () => {
  const ctx = {
    ...base,
    tasks: [{ id: "a", title: "Physics lab", minutes: 60, completed: false }, { id: "b", title: "Physics homework", minutes: 60, completed: false }],
    plan: { sessions: [], busy: [], freeBlocks: [] },
  };
  assert.equal(run("move physics lab to tomorrow", [], ctx).actions[0].args.taskId, "a");
  assert.equal(run("move physics homework to tomorrow", [], ctx).actions[0].args.taskId, "b");
  const vague = run("move physics to tomorrow", [], ctx);
  assert.ok(vague.clarify, "asks which");
  assert.ok(vague.clarify.options.every((o) => /\[\[id:/.test(o.text)), "different names carry ids too: the name glued onto the sentence never reached the right place");
  assert.equal(run(vague.clarify.options[1].text, [], ctx).actions[0].args.taskId, "b");
  // and the name typed as the answer settles it, as before
  const h = turn([], "move physics to tomorrow", vague.reply);
  assert.equal(run("physics homework", h, ctx).actions[0].args.taskId, "b");
});

test("asking about a look-alike that has one copy done or deleted is not ambiguous any more", () => {
  const ctx = { ...base, tasks: base.tasks.filter((t) => t.id !== "b"), plan: { ...base.plan, sessions: base.plan.sessions.filter((s) => s.refId !== "b") } };
  assert.equal(run("move deep learning project to tomorrow", [], ctx).actions[0].args.taskId, "a");
});

test("a token with an unknown id does nothing strange", () => {
  const r = run("move physics lab to tomorrow [[id:nope]]");
  assert.equal(r.actions[0].args.taskId, "c");
});

test("the ids never leak into a reply", () => {
  const r = run("move Deep learning project to tomorrow [[id:a]]");
  assert.ok(!/\[\[id/.test(r.reply));
  assert.ok(!/\[\[id/.test(JSON.stringify(r.actions)));
});

test("two vague names in one sentence: asked twice, and the right pair is swapped", () => {
  const ctx = {
    ...base,
    tasks: [
      { id: "a", title: "Physics lab", minutes: 60, completed: false }, { id: "b", title: "Physics homework", minutes: 45, completed: false },
      { id: "c", title: "Maths", minutes: 30, completed: false }, { id: "d", title: "Maths", minutes: 50, due: "2026-10-29", completed: false },
    ],
    plan: {
      sessions: [
        { id: "sa", title: "Physics lab", kind: "TASK", refId: "a", start: "20:00", end: "21:00", status: "PLANNED" },
        { id: "sb", title: "Physics homework", kind: "TASK", refId: "b", start: "21:15", end: "22:00", status: "PLANNED" },
        { id: "sc", title: "Maths", kind: "TASK", refId: "c", start: "22:10", end: "22:40", status: "PLANNED" },
        { id: "sd", title: "Maths", kind: "TASK", refId: "d", start: "22:50", end: "23:30", status: "PLANNED" },
      ],
      busy: [], freeBlocks: [],
    },
  };
  const q1 = interpret("swap physics with maths", ctx, []);
  assert.match(q1.reply, /Which/);
  const q2 = interpret(q1.clarify.options[1].text, ctx, []); // Physics homework
  assert.equal(q2.actions.length, 0);
  assert.match(q2.reply, /You have 2 called “Maths”/);
  const done = interpret(q2.clarify.options[1].text, ctx, []); // the second Maths
  assert.deepEqual([done.actions[0].type, done.actions[0].args.taskA, done.actions[0].args.taskB], ["swap_tasks", "b", "d"]);
});

test("a progress note about a name that matches two tasks asks which, instead of making a junk task", () => {
  const r = interpret("worked on deep learning project for 30 min", base, []);
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /Which/);
  const logged = interpret(r.clarify.options[1].text, base, []);
  assert.deepEqual([logged.actions[0].type, logged.actions[0].args.taskId, logged.actions[0].args.minutes], ["log_progress", "b", 30]);
});

test("every kind of command with a vague name: every chip leads somewhere, nothing loops", () => {
  const ctx = {
    ...base,
    tasks: [
      { id: "a", title: "Physics lab", minutes: 60, due: "2026-10-27", completed: false }, { id: "b", title: "Physics homework", minutes: 45, completed: false },
      { id: "c", title: "Maths", minutes: 30, completed: false }, { id: "d", title: "Maths", minutes: 50, due: "2026-10-29", completed: false },
    ],
    plan: {
      sessions: [
        { id: "sa", title: "Physics lab", kind: "TASK", refId: "a", start: "20:00", end: "21:00", status: "PLANNED" },
        { id: "sb", title: "Physics homework", kind: "TASK", refId: "b", start: "21:15", end: "22:00", status: "PLANNED" },
        { id: "sc", title: "Maths", kind: "TASK", refId: "c", start: "22:10", end: "22:40", status: "PLANNED" },
        { id: "sd", title: "Maths", kind: "TASK", refId: "d", start: "22:50", end: "23:30", status: "PLANNED" },
      ],
      busy: [], freeBlocks: [],
    },
  };
  const sentences = [
    "move physics to tomorrow", "move physics to 11pm", "move maths to tomorrow", "move maths to 10pm", "mark physics done", "mark maths done",
    "i finished maths", "i finished physics", "delete physics", "delete maths", "postpone maths", "postpone physics", "skip maths", "skip physics",
    "start maths", "start physics", "push maths to friday", "push physics to friday", "change maths to 2 hours", "make physics 2 hours",
    "put maths in the evening", "worked on maths for 30 min", "spent 20 minutes on physics", "do maths now", "reschedule maths to tomorrow",
  ];
  const stuck = [];
  for (const t of sentences) {
    const q = interpret(t, ctx, []);
    if (!q.clarify) continue;
    for (const o of q.clarify.options) {
      const r = interpret(o.text, ctx, []);
      if (r.actions.length === 0) stuck.push(`${t} -> ${o.label}: ${r.reply.split("\n")[0]}`);
    }
  }
  assert.deepEqual(stuck, []);
});

// ============================================================================ the phone's real ids, and one task in several sessions
// A session's id on the phone looks like "2026-10-24|TASK|<task id>|<start minute>". An earlier version of the choice
// markers only accepted letters, digits and dashes, so on the phone the choice never took effect and the question repeated.
const T1 = "3f2a9c1e-5b7d-4e21-9a10-6c8d2f4b7a11";
const T2 = "8b1d4e77-2c30-4f5a-b6e9-0a7c1d3e5f22";
const T3 = "c4e6a8b0-1d2f-4a3b-8c5d-7e9f0a1b2c33";
const sid = (ref, start) => `2026-10-24|TASK|${ref}|${start}`;
const real = {
  today: "2026-10-24",
  nowMin: 19 * 60 + 6,
  tasks: [
    { id: T1, title: "Deep learning project", minutes: 120, due: "2026-10-24", completed: false },
    { id: T2, title: "MLP Project", minutes: 60, due: "2026-10-25", completed: false },
    { id: T3, title: "Physics lab", minutes: 30, completed: false },
  ],
  habits: [],
  plan: {
    sessions: [
      { id: sid(T1, 1160), title: "Deep learning project", kind: "TASK", refId: T1, start: "19:20", end: "19:50", status: "PLANNED" },
      { id: sid(T3, 1195), title: "Physics lab", kind: "TASK", refId: T3, start: "19:55", end: "20:25", status: "PLANNED" },
      { id: sid(T1, 1230), title: "Deep learning project", kind: "TASK", refId: T1, start: "20:30", end: "22:00", status: "PLANNED" },
      { id: sid(T2, 1330), title: "MLP Project", kind: "TASK", refId: T2, start: "22:10", end: "23:10", status: "PLANNED" },
    ],
    busy: [], freeBlocks: [],
  },
  tomorrow: { sessions: [], freeMinutes: 400 },
};

test("ids with | and : in them work (the bug on the phone: choosing never took effect)", () => {
  const twin = {
    ...real,
    tasks: [...real.tasks, { id: "99999999-0000-4000-8000-000000000001", title: "Physics lab", minutes: 60, due: "2026-10-26", completed: false }],
    plan: { ...real.plan, sessions: [...real.plan.sessions, { id: sid("99999999-0000-4000-8000-000000000001", 1400), title: "Physics lab", kind: "TASK", refId: "99999999-0000-4000-8000-000000000001", start: "23:20", end: "23:50", status: "PLANNED" }] },
  };
  const q = interpret("move physics lab to tomorrow", twin, []);
  assert.match(q.reply, /You have 2 called/);
  assert.ok(q.clarify.options.every((o) => o.text.includes("|") || o.text.includes("-")));
  for (const [i, expected] of [[0, T3], [1, "99999999-0000-4000-8000-000000000001"]]) {
    const r = interpret(q.clarify.options[i].text, twin, []);
    assert.deepEqual([r.actions[0].type, r.actions[0].args.taskId], ["defer_task", expected]);
  }
  // and by words, with the history exactly as the app sends it
  const h = turn([], "move physics lab to tomorrow", q.reply);
  assert.equal(interpret("first", twin, h).actions[0].args.taskId, T3);
  assert.equal(interpret("second", twin, h).actions[0].args.taskId, "99999999-0000-4000-8000-000000000001");
});

test("a marker with a session id on the phone's format is taken out of the sentence", () => {
  const id = sid(T1, 1160);
  const r = interpret(`move physics lab to tomorrow [[id:${id}]] [[no:${sid(T1, 1230)}]]`, real, []);
  assert.ok(!r.reply.includes("[["), r.reply);
  assert.equal(r.actions[0].args.taskId, T3);
});

test("one task planned as two sessions is ONE task: no 'which session?' for a name (the screenshot)", () => {
  for (const sentence of ["swap deep learning project with mlp project", "swap mlp project with deep learning project", "swap deep learning project and physics lab"]) {
    const r = interpret(sentence, real, []);
    assert.equal(r.clarify, null, sentence);
    assert.equal(r.actions[0].type, "swap_tasks", sentence);
  }
  const swap = interpret("swap deep learning project with mlp project", real, []).actions[0];
  assert.deepEqual([swap.args.taskA, swap.args.taskB], [T1, T2]);
  assert.match(interpret("swap deep learning project with mlp project", real, []).reply, /19:20–19:50, 20:30–22:00/);
  assert.match(interpret("swap deep learning project with mlp project", real, []).reply, /“MLP Project” will now come first/);
});

test("moving a task that has two sessions moves all of it", () => {
  const r = interpret("move deep learning project to 11pm", real, []);
  assert.equal(r.clarify, null);
  assert.deepEqual([r.actions[0].type, r.actions[0].args], ["move_task", { taskId: T1, time: "23:00", flexible: false }]);
  assert.match(r.reply, /all 2 sessions, 2 h/);
  assert.equal(interpret("move deep learning project after physics lab", real, []).actions[0].type, "move_task");
  assert.equal(interpret("do deep learning project now", real, []).actions[0].type, "move_task");
  assert.ok(validateAction(r.actions[0]).action);
});

test("a task with ONE session still moves with move_session; slots (by time or position) are moved one at a time", () => {
  assert.equal(interpret("move physics lab to 11pm", real, []).actions[0].type, "move_session");
  assert.equal(interpret("move mlp project to 8pm", real, []).actions[0].type, "move_session");
  const byTime = interpret("move the 8:30 one to 11pm", real, []);
  assert.deepEqual([byTime.actions[0].type, byTime.actions[0].args.sessionId], ["move_session", sid(T1, 1230)]);
  const first = interpret("move the first one to 11pm", real, []);
  assert.deepEqual([first.actions[0].type, first.actions[0].args.sessionId], ["move_session", sid(T1, 1160)]);
});

test("skipping a task with two sessions skips the next one, without asking", () => {
  const r = interpret("skip deep learning project", real, []);
  assert.equal(r.clarify, null);
  assert.deepEqual([r.actions[0].type, r.actions[0].args.sessionId], ["skip_session", sid(T1, 1160)]);
});

test("a day to put it off (tomorrow) is still about the task", () => {
  assert.deepEqual(interpret("move deep learning project to tomorrow", real, []).actions[0].args, { taskId: T1, until: "2026-10-25" });
});

test("two DIFFERENT tasks with the same name, each in two sessions: asked once, by task, and swapped by id", () => {
  const A = "aaaaaaaa-0000-4000-8000-000000000001";
  const B = "bbbbbbbb-0000-4000-8000-000000000002";
  const ctx = {
    ...real,
    tasks: [{ id: A, title: "Maths", minutes: 90, due: "2026-10-26", completed: false }, { id: B, title: "Maths", minutes: 60, due: "2026-10-29", completed: false }, real.tasks[2]],
    plan: {
      sessions: [
        { id: sid(A, 1200), title: "Maths", kind: "TASK", refId: A, start: "20:00", end: "20:45", status: "PLANNED" },
        { id: sid(B, 1250), title: "Maths", kind: "TASK", refId: B, start: "20:50", end: "21:20", status: "PLANNED" },
        { id: sid(A, 1300), title: "Maths", kind: "TASK", refId: A, start: "21:40", end: "22:25", status: "PLANNED" },
        { id: sid(B, 1360), title: "Maths", kind: "TASK", refId: B, start: "22:40", end: "23:10", status: "PLANNED" },
        { id: sid(T3, 1420), title: "Physics lab", kind: "TASK", refId: T3, start: "23:40", end: "23:55", status: "PLANNED" },
      ],
      busy: [], freeBlocks: [],
    },
  };
  const q = interpret("swap maths with physics lab", ctx, []);
  assert.match(q.reply, /You have 2 called “Maths”\. Which one do you mean\?/);
  assert.match(q.reply, /1\. 20:00–20:45, 21:40–22:25 · due Mon 26 Oct/);
  assert.match(q.reply, /2\. 20:50–21:20, 22:40–23:10 · due Thu 29 Oct/);
  assert.equal(q.clarify.options.length, 2);
  const r = interpret(q.clarify.options[1].text, ctx, []);
  assert.deepEqual([r.actions[0].type, r.actions[0].args.taskA, r.actions[0].args.taskB], ["swap_tasks", B, T3]);
});

test("swap_tasks and move_task are valid actions and refuse nonsense", () => {
  assert.ok(validateAction({ type: "swap_tasks", args: { taskA: T1, taskB: T2 } }).action);
  assert.ok(!validateAction({ type: "swap_tasks", args: { taskA: T1, taskB: T1 } }).action);
  assert.ok(!validateAction({ type: "swap_tasks", args: { taskA: T1 } }).action);
  assert.ok(validateAction({ type: "move_task", args: { taskId: T1, time: "23:00" } }).action);
  assert.ok(!validateAction({ type: "move_task", args: { taskId: T1, time: "25:99" } }).action);
});

// ------------------------------------------------------------------------------- the endpoint
let server, url;
test.describe("the endpoint", () => {
  test.before(async () => {
    const app = express();
    app.use(express.json());
    app.use("/api/chat", buildRouter({ apiKey: "" }));
    server = await new Promise((resolve) => { const s = app.listen(0, () => resolve(s)); });
    url = `http://127.0.0.1:${server.address().port}/api/chat`;
  });
  test.after(() => server.close());
  const post = (messages) =>
    fetch(url, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${signAccessToken("c1")}` }, body: JSON.stringify({ messages, context: base }) });

  test("a whole conversation: ask, repeat the name, then pick by words", async () => {
    let history = [{ role: "user", text: "move Deep learning project to tomorrow" }];
    let body = await (await post(history)).json();
    assert.match(body.reply, /You have 2 called/);
    history = [...history, { role: "assistant", text: body.reply }, { role: "user", text: "Deep learning project" }];
    body = await (await post(history)).json();
    assert.match(body.reply, /doesn't tell them apart/);
    history = [...history, { role: "assistant", text: body.reply }, { role: "user", text: "the one due friday" }];
    body = await (await post(history)).json();
    assert.deepEqual([body.actions[0].type, body.actions[0].args.taskId], ["defer_task", "b"]);
  });

  test("a chip's text (with its ids) is accepted as a message, and does what the chip says", async () => {
    const asked = await (await post([{ role: "user", text: "move Deep learning project to tomorrow" }])).json();
    for (const [i, expected] of [[0, "a"], [1, "b"]]) {
      const res = await post([{ role: "user", text: asked.clarify.options[i].text }]);
      assert.equal(res.status, 200);
      assert.equal((await res.json()).actions[0].args.taskId, expected);
    }
  });

  test("an id on its own is enough to tell same-named tasks apart", async () => {
    const res = await post([{ role: "user", text: "move Deep learning project to tomorrow [[id:b]]" }]);
    assert.equal((await res.json()).actions[0].args.taskId, "b");
  });
});

test("the AI is told what the markers on a tapped choice mean", () => {
  const ai = require("../lib/chat/ai");
  const system = ai.buildSystem(base);
  assert.match(system, /\[\[id:X\]\] is the exact task or session they mean/);
  assert.match(system, /Never mention these markers/);
  assert.match(system, /tell them apart by id, due date or time, never by name alone/);
});

