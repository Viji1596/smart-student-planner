const test = require("node:test");
const assert = require("node:assert/strict");
const { interpret } = require("../lib/chat/rules");
const { validateAction } = require("../lib/chat/actions");

const TODAY = "2026-10-24"; // Saturday
const ctx = {
  today: TODAY,
  nowMin: 11 * 60,
  tasks: [
    { id: "t1", title: "MLP Project", minutes: 120, priority: "High", due: "2026-10-25", completed: false },
    { id: "t2", title: "DBMS lab record", minutes: 60, due: "2026-10-28", completed: false },
  ],
  habits: [{ id: "h1", title: "Coding practice", minutes: 45, kind: "DAILY" }],
  plan: { sessions: [{ id: "s1", title: "MLP Project", kind: "TASK", refId: "t1", start: "14:00", end: "16:00", status: "PLANNED" }], busy: [], freeBlocks: [] },
};
const empty = { today: TODAY, nowMin: 600, tasks: [], habits: [], plan: { sessions: [] } };
const run = (text, c = empty) => interpret(text, c, []);
const add = (text, c = empty) => {
  const r = run(text, c);
  assert.equal(r.actions.length, 1, `${text} -> ${JSON.stringify(r)}`);
  assert.equal(r.actions[0].type, "add_task", text);
  assert.ok(validateAction(r.actions[0]).action, text);
  return r.actions[0].args;
};

// -------------------------------------------------------------------- how am I doing, asked any way
test("the performance report, asked in every way people ask it", () => {
  const sevenDays = [
    "how am i doing", "How am I doing?", "how i am doing", "how am i performing", "how is my performance", "progress", "my progress",
    "am i on track", "how was this week", "am i doing well", "how much have i done", "how much did i study this week",
    "give me my report", "report", "my stats", "summary", "weekly report", "how is my week going", "how have i been doing lately",
    "how productive was i", "show my progress", "how did i do this week", "whats my progress", "how am i doing with my habits",
    "am i falling behind", "hows my progress", "wat is my progress", "how are my streaks", "performance",
  ];
  for (const t of sevenDays) {
    const r = run(t, ctx);
    assert.equal(r.actions[0] && r.actions[0].type, "show_report", `${t} -> ${JSON.stringify(r.actions)} ${r.reply.slice(0, 50)}`);
    assert.equal(r.actions[0].args.days, 7, t);
  }
});

test("a day, two weeks, a month, N days", () => {
  assert.equal(run("how did i do today", ctx).actions[0].args.days, 1);
  assert.equal(run("how am i doing today", ctx).actions[0].args.days, 1);
  assert.equal(run("how much did i do so far today", ctx).actions[0].args.days, 1);
  assert.equal(run("report for the last 14 days", ctx).actions[0].args.days, 14);
  assert.equal(run("how was my month", ctx).actions[0].args.days, 30);
  assert.equal(run("progress over the last two weeks", ctx).actions[0].args.days, 14);
});

test("questions about finishing are not 'I finished'", () => {
  for (const t of ["did i finish mlp project?", "how did i do today", "how much have i done", "have i finished the dbms lab record"]) {
    assert.ok(!run(t, ctx).actions.some((a) => a.type === "complete_task"), t);
  }
  assert.equal(run("i finished mlp project", ctx).actions[0].type, "complete_task");
});

test("'am i falling behind' is about the report, not a feeling", () => {
  assert.equal(run("am i falling behind", ctx).actions[0].type, "show_report");
  assert.equal(run("i'm falling behind on everything", ctx).actions[0].type, "show_focus", "said as a feeling: support");
});

test("questions about a task are still answered as such", () => {
  assert.match(run("how long is mlp project", ctx).reply, /estimated at 2 h/);
  assert.equal(run("what is due tomorrow", ctx).actions[0].type, "show_tasks");
});

// ----------------------------------------------------------------------- 'add' anywhere
test("the word 'add' anywhere in the sentence adds a task", () => {
  assert.deepEqual(add("MLP project high priority due tomorrow please add"), { title: "MLP project", priority: "High", due: "2026-10-25" });
  assert.deepEqual(add("viva prep, priority low, due next friday, add it"), { title: "Viva prep", priority: "Low", due: "2026-10-30" });
  assert.equal(add("hey could you just add this for me: viva prep").title, "Viva prep");
  assert.equal(add("add it: read paper").title, "Read paper");
  assert.equal(add("note down call home urgent").priority, "High");
  assert.equal(add("put OS notes in my task list high priority by monday").title, "OS notes");
});

test("an add-word inside a question or a habit is not a task", () => {
  assert.equal(run("should i add more tasks?").actions.length, 0);
  assert.equal(run("can i add a habit later").actions.length, 0);
  assert.equal(run("add habit gym every day").actions[0].type, "add_habit");
});

// --------------------------------------------------------------------------- 'due' = the date
test("everything after 'due' or 'deadline' is the date", () => {
  assert.equal(add("add task essay due friday").due, "2026-10-30");
  assert.equal(add("add task essay deadline: 30th").due, "2026-10-30");
  assert.equal(add("add task essay due 28").due, "2026-10-28");
  assert.equal(add("add task essay due 28 oct high priority").due, "2026-10-28");
  assert.equal(add("add task essay deadline is next monday").due, "2026-10-26");
  assert.equal(add("add task essay submit by 3 nov").due, "2026-11-03");
  assert.equal(add("add task essay due nxt frday").due, "2026-10-30", "typos in the date are corrected");
  assert.equal(add("add task essay due tomorrow 1 hour").minutes, 60);
});

test("the due words never stay in the title", () => {
  for (const t of ["add task essay due friday", "add task essay deadline: 30th", "add task essay due 28 oct", "add task essay submit by friday"]) {
    assert.equal(add(t).title, "Essay", t);
  }
});

test("a due date that can't be read is asked about, never quietly lost", () => {
  const r = run("add task essay due blah blah");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /couldn't read the due date “blah blah”/);
  assert.deepEqual(r.clarify.options.map((o) => o.label), ["Due tomorrow", "Add without a due date"]);
  // both buttons work
  assert.equal(run(r.clarify.options[0].text).actions[0].args.due, "2026-10-25");
  const without = run(r.clarify.options[1].text).actions[0];
  assert.equal(without.type, "add_task");
  assert.equal(without.args.due, undefined);
});

test("'due' with nothing after it just adds the task", () => {
  assert.equal(add("add task read paper due").title, "Read paper");
});

// ------------------------------------------------------------ priority: before and after
test("priority is read on either side of the word 'priority'", () => {
  const p = (t) => add(`add task essay ${t}`).priority;
  assert.equal(p("high priority"), "High");
  assert.equal(p("priority high"), "High");
  assert.equal(p("priority: low"), "Low");
  assert.equal(p("priority is medium"), "Medium");
  assert.equal(p("low prio"), "Low");
  assert.equal(p("high-priority"), "High");
  assert.equal(p("p1"), "High");
  assert.equal(p("p3"), "Low");
  assert.equal(p("priority 2"), "Medium");
  assert.equal(p("normal priority"), "Medium");
  assert.equal(p("urgent"), "High");
  assert.equal(p("asap"), "High");
  assert.equal(p("top priority"), "High");
  assert.equal(p("very important"), "High");
});

test("priority words are not left in the title", () => {
  for (const t of ["high priority essay", "essay priority high", "essay p1", "urgent essay"]) assert.equal(add(`add task ${t}`).title, "Essay", t);
});

test("'low' or 'high' in a title are not a priority", () => {
  assert.equal(add("add task low level design").title, "Low level design");
  assert.equal(add("add task high school reunion").priority, undefined);
});

// --------------------------------------------------------------------------- duplicates
test("adding a name that already exists asks, instead of quietly duplicating or quietly editing", () => {
  const r = run("MLP project high priority due tomorrow please add it", ctx);
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /already have “MLP Project” \(due Sun 25 Oct\)/);
  const labels = r.clarify.options.map((o) => o.label);
  assert.ok(labels.includes("Update its due date to Sun 25 Oct"));
  assert.ok(labels.includes("Add another one"));
  const another = run(r.clarify.options.find((o) => o.label === "Add another one").text, ctx);
  assert.equal(another.actions[0].type, "add_task", "'add another' goes through");
  assert.equal(run(r.clarify.options[0].text, ctx).actions[0].type, "update_task");
});

test("a different name is just added", () => {
  assert.equal(add("add task DBMS viva", ctx).title, "DBMS viva");
});

// ------------------------------------------------------------------- naming a task alone
test("just naming a task offers what to do with it, and every offer works", () => {
  for (const t of ["mlp project", "dbms lab record?", "mlp"]) {
    const r = run(t, ctx);
    assert.equal(r.actions.length, 0, t);
    assert.match(r.reply, /What would you like to do with/, t);
    assert.deepEqual(r.clarify.options.map((o) => o.label), ["Mark it done", "Move its session", "Do it tomorrow", "Delete it"]);
  }
  const options = run("mlp project", ctx).clarify.options;
  assert.equal(run(options[0].text, ctx).actions[0].type, "complete_task");
  assert.equal(run(options[1].text, ctx).clarify.question.startsWith("Where should I move"), true);
  assert.equal(run(options[2].text, ctx).actions[0].type, "defer_task");
  assert.equal(run(options[3].text, ctx).actions[0].type, "delete_task");
  assert.equal(run(options[3].text, ctx).actions[0].confirm === undefined, true); // confirmation is added by the route
});

test("a long unrelated sentence that happens to contain a task name isn't hijacked", () => {
  assert.equal(run("honestly i was thinking about the weather and my friends and the mlp project yesterday evening").clarify, null);
});
