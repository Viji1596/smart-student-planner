const test = require("node:test");
const assert = require("node:assert/strict");
const express = require("express");

process.env.JWT_SECRET = "test-secret-test-secret-123";
const { interpret } = require("../lib/chat/rules");
const { validateAction } = require("../lib/chat/actions");
const { buildRouter } = require("../routes/chatRoutes");
const { signAccessToken } = require("../lib/tokens");
const { VERSION } = require("../lib/version");
const ai = require("../lib/chat/ai");

const TODAY = "2026-10-24";
const base = {
  today: TODAY,
  nowMin: 11 * 60,
  tasks: [
    { id: "t1", title: "MLP Project", minutes: 120, priority: "High", due: "2026-10-25", completed: false },
    { id: "t2", title: "DBMS lab record", minutes: 60, due: "2026-10-28", completed: false },
    { id: "t3", title: "Chemistry quiz prep", minutes: 40, completed: false },
    { id: "t4", title: "Chemistry assignment", minutes: 90, completed: false },
  ],
  habits: [],
  plan: {
    sessions: [{ id: "s2", title: "DBMS lab record", kind: "TASK", refId: "t2", start: "16:00", end: "17:00", status: "PLANNED" }],
    busy: [{ label: "Dinner", kind: "MEAL", start: "20:00", end: "20:40" }],
    freeBlocks: [{ label: "free", start: "17:00", end: "20:00" }],
  },
};
const withFocus = (taskId, title, extra = {}) => ({ ...base, ...extra, focus: { taskId, title } });
const run = (text, ctx = base, history = []) => interpret(text, ctx, history);
const one = (text, ctx = base, history = []) => {
  const r = run(text, ctx, history);
  assert.equal(r.actions.length, 1, `${text} -> ${JSON.stringify(r)}`);
  assert.ok(validateAction(r.actions[0]).action, `${text}: ${JSON.stringify(r.actions[0])} ${validateAction(r.actions[0]).error}`);
  return r.actions[0];
};

// --------------------------------------------------------------------- the exact example
test("add 'mlp project', then 'move it to 9pm': it means the task just added", () => {
  // the app remembers what the last action touched and sends it along (here: the new task, no session today)
  const a = one("move it to 9pm", withFocus("t1", "MLP Project"));
  assert.deepEqual([a.type, a.args], ["place_task", { taskId: "t1", time: "21:00", flexible: false }]);
});

test("the same thing without the app's help: it is found from the conversation", () => {
  const history = [
    { role: "user", text: "add mlp project" },
    { role: "assistant", text: "Sure, adding the task “MLP project”.\n✓ Added “MLP project” (30 min).\nIt isn't in today's plan (it suits another day better, or today is full)." },
  ];
  const a = one("move it to 9pm", base, history);
  assert.deepEqual([a.type, a.args.taskId, a.args.time], ["place_task", "t1", "21:00"]);
});

test("when the task already has a session today, 'it' moves that session", () => {
  const a = one("move it to 9pm", withFocus("t2", "DBMS lab record"));
  assert.deepEqual([a.type, a.args], ["move_session", { sessionId: "s2", time: "21:00", flexible: false }]);
});

test("a bare hour is the next such time still ahead", () => {
  assert.equal(one("move it to 9", withFocus("t1", "MLP Project")).args.time, "21:00");
  assert.equal(one("do it at 7", withFocus("t1", "MLP Project")).args.time, "19:00");
});

// ------------------------------------------------------------------------ placing a task
test("scheduling a task that has no session today: after, before, now, part of the day", () => {
  const f = withFocus("t1", "MLP Project");
  assert.deepEqual(one("move it after dinner", f).args, { taskId: "t1", time: "20:40", flexible: true });
  assert.equal(one("put it in the evening", f).args.time, "18:00");
  assert.equal(one("do it now", f).args.time, "11:00");
  // before dinner: the 90-minute cap, a 5-minute gap
  assert.equal(one("move it before dinner", f).args.time, "18:25");
});

test("the session made for a task is at most 90 minutes, whatever is left", () => {
  // MLP Project needs 120 minutes: the app caps the session; the server only chooses the time
  assert.equal(one("schedule mlp project at 6pm").type, "place_task");
});

test("'move mlp project' with nowhere to move it asks, with free slots, and the offers work", () => {
  const r = run("move mlp project");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /Where should I move “MLP Project”/);
  assert.ok(r.clarify.options.some((o) => o.text === "move MLP Project to 17:00"));
  for (const option of r.clarify.options) assert.equal(run(option.text).actions[0].type, option.text.endsWith("tomorrow") ? "defer_task" : "place_task", option.text);
});

// -------------------------------------------------------------------- "it" in other commands
test("'it' works in other commands too", () => {
  const f = withFocus("t1", "MLP Project");
  assert.deepEqual(one("mark it done", f), { type: "complete_task", args: { taskId: "t1" } });
  assert.deepEqual(one("I finished it", f), { type: "complete_task", args: { taskId: "t1" } });
  assert.deepEqual(one("make it 2 hours", f).args, { taskId: "t1", minutes: 120 });
  assert.deepEqual(one("set it to low priority", f).args, { taskId: "t1", priority: "Low" });
  assert.deepEqual(one("do it tomorrow", f).args, { taskId: "t1", until: "2026-10-25" });
  assert.equal(one("delete it", f).type, "delete_task");
  assert.match(run("delete it", f).reply, /confirm/i);
});

test("'this' and 'that' work like 'it'", () => {
  const f = withFocus("t1", "MLP Project");
  assert.equal(one("mark that done", f).args.taskId, "t1");
  assert.equal(one("move this one to 8pm", f).args.time, "20:00");
  assert.equal(one("postpone that", f).args.taskId, "t1");
});

test("a task named in the sentence wins over 'it'", () => {
  const a = one("move dbms lab record to 9pm and forget it", withFocus("t1", "MLP Project"));
  assert.equal(a.args.sessionId, "s2");
});

test("'this week', 'that evening', 'it' as a subject are not taken for the task", () => {
  const f = withFocus("t1", "MLP Project");
  assert.equal(run("what is due this week", f).actions[0].type, "show_tasks");
  assert.equal(run("move dbms lab record to that evening", f).actions[0].args.sessionId, "s2");
  assert.equal(run("how is it going", f).actions.length === 0 || run("how is it going", f).actions[0].type !== "complete_task", true);
});

test("'add it' adds, it doesn't re-use the remembered task", () => {
  const r = run("viva prep priority low due friday, please add it", withFocus("t1", "MLP Project"));
  assert.equal(r.actions[0].type, "add_task");
  assert.equal(r.actions[0].args.title, "Viva prep");
});

test("with nothing remembered, 'it' is not guessed", () => {
  assert.equal(run("move it to 9pm").actions.length, 0);
  assert.equal(run("delete it").actions.length, 0);
});

test("a task finished or deleted earlier is not offered as 'it'", () => {
  // the app reports the focus task, but it is not among the open tasks any more
  const r = run("move it to 9pm", { ...base, tasks: base.tasks.filter((t) => t.id !== "t1"), focus: { taskId: "t1", title: "MLP Project" } });
  assert.equal(r.actions.length, 0);
});

// ----------------------------------------------------------------------- short follow-up answers
const asked = (question, userBefore = "move reading") => [
  { role: "user", text: userBefore },
  { role: "assistant", text: question },
];

test("'9pm' after 'Where should I move it?'", () => {
  const history = asked("Where should I move “MLP Project”? You can say a time (“to 6pm”), “after dinner”.");
  const a = one("9pm", base, history);
  assert.deepEqual([a.type, a.args.taskId, a.args.time], ["place_task", "t1", "21:00"]);
  assert.equal(one("after dinner", base, history).args.time, "20:40");
  assert.equal(one("to 8pm", base, history).args.time, "20:00");
  assert.equal(one("tomorrow", base, history).type, "defer_task");
});

test("a date after 'When should it be due?'", () => {
  const history = asked("When should “MLP Project” be due? For example: “friday”.", "change mlp project due");
  assert.deepEqual(one("friday", base, history).args, { taskId: "t1", due: "2026-10-30" });
});

test("'every day' after 'How often should it happen?'", () => {
  const history = asked("How often should “Meditation” happen?", "add habit meditation");
  assert.deepEqual(one("every day", base, history).args, { title: "Meditation", kind: "DAILY" });
});

test("the name of a task after 'What should the task be called?'", () => {
  const history = asked("What should the task be called? I add them one at a time.", "add task due friday");
  assert.deepEqual(one("viva prep", base, history).args, { title: "Viva prep", due: "2026-10-30" });
});

test("a date after 'I couldn't read the due date'", () => {
  const history = asked("I couldn't read the due date “blah”. When is “Essay” due?", "add task essay due blah");
  assert.equal(one("next monday", base, history).args.due, "2026-10-26");
});

test("a word or two after 'Which task do you mean?'", () => {
  const history = asked("Which task do you mean?", "mark chemistry done");
  assert.equal(one("quiz prep", base, history).args.taskId, "t3");
  assert.equal(one("the assignment", base, history).args.taskId, "t4");
});

test("replies after 'What would you like to do with it?'", () => {
  const history = asked("What would you like to do with “MLP Project”?", "mlp project");
  assert.equal(one("done", base, history).type, "complete_task");
  assert.equal(one("delete", base, history).type, "delete_task");
  assert.equal(one("tomorrow", base, history).type, "defer_task");
  assert.equal(one("9pm", base, history).type, "place_task");
  assert.equal(one("postpone", base, history).type, "defer_task");
});

test("a fresh command after a question is a fresh command, not an answer", () => {
  const history = asked("Where should I move “MLP Project”?");
  assert.equal(one("add task viva prep", base, history).type, "add_task");
  assert.equal(run("what's my plan today", base, history).actions[0].type, "show_plan");
  const long = run("honestly i think i will do something else entirely this evening so forget it", base, history);
  assert.equal(long.actions.length === 0 || long.actions[0].type !== "place_task", true);
});

test("a follow-up needs the assistant's question right before it", () => {
  const history = [
    { role: "assistant", text: "Where should I move “MLP Project”?" },
    { role: "user", text: "thanks" },
    { role: "assistant", text: "You're welcome!" },
  ];
  assert.equal(run("9pm", base, history).actions.length, 0);
});

// ------------------------------------------------------------------------------- the route
let server, url;
const startWith = (deps) =>
  new Promise((resolve) => {
    const app = express();
    app.use(express.json());
    app.use("/api/chat", buildRouter(deps));
    const s = app.listen(0, () => resolve(s));
  });
const post = (payload) =>
  fetch(url, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${signAccessToken("c1")}` }, body: JSON.stringify(payload) });

test.describe("the endpoint", () => {
  test.before(async () => {
    server = await startWith({ apiKey: "" });
    url = `http://127.0.0.1:${server.address().port}/api/chat`;
  });
  test.after(() => server.close());

  test("it accepts the focus and uses it", async () => {
    const res = await post({ messages: [{ role: "user", text: "move it to 9pm" }], context: withFocus("t1", "MLP Project") });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.deepEqual(body.actions[0], { type: "place_task", args: { taskId: "t1", time: "21:00", flexible: false }, confirm: false });
  });

  test("every answer says which server version gave it", async () => {
    const body = await (await post({ messages: [{ role: "user", text: "hello" }], context: base })).json();
    assert.equal(body.version, VERSION);
  });

  test("a null or missing focus is fine; a malformed one is refused", async () => {
    assert.equal((await post({ messages: [{ role: "user", text: "hi" }], context: { ...base, focus: null } })).status, 200);
    assert.equal((await post({ messages: [{ role: "user", text: "hi" }], context: base })).status, 200);
    assert.equal((await post({ messages: [{ role: "user", text: "hi" }], context: { ...base, focus: { taskId: "", title: "x" } } })).status, 400);
  });
});

test("the AI is told what 'it' means", () => {
  const system = ai.buildSystem(withFocus("t1", "MLP Project"));
  assert.match(system, /FOCUS: \{"taskId":"t1","title":"MLP Project"\}/);
  assert.match(system, /they mean the FOCUS task/);
  assert.match(system, /place_task/);
  assert.match(ai.buildSystem(base), /FOCUS: none yet/);
});
