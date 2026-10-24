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

test("moving a session by time, the other copy is left alone", () => {
  const r = question("move deep learning project to 11pm");
  assert.match(r.reply, /Which one do you mean\?/);
  const a = run(r.clarify.options[1].text);
  assert.deepEqual([a.actions[0].type, a.actions[0].args.sessionId, a.actions[0].args.time], ["move_session", "sb", "23:00"]);
});

test("swapping with a look-alike asks which, then swaps the right pair", () => {
  const r = question("swap deep learning project with physics lab");
  assert.match(r.reply, /Which session do you mean\?/);
  assert.ok(r.clarify.options.every((o) => /\[\[id:/.test(o.text)));
  const labels = r.clarify.options.map((o) => o.label);
  assert.match(labels[0], /20:00–21:00/);
  const second = run(r.clarify.options[1].text);
  assert.deepEqual([second.actions[0].type, second.actions[0].args.sessionA, second.actions[0].args.sessionB], ["swap_sessions", "sb", "sc"]);
  // by words
  const h = turn([], "swap deep learning project with physics lab", r.reply);
  const byWord = run("the first one", h);
  assert.deepEqual([byWord.actions[0].args.sessionA, byWord.actions[0].args.sessionB], ["sa", "sc"]);
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
  assert.deepEqual([done.actions[0].type, done.actions[0].args.sessionA, done.actions[0].args.sessionB], ["swap_sessions", "sb", "sd"]);
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

