const test = require("node:test");
const assert = require("node:assert/strict");
const { interpret, cleanTitle, extractSlots } = require("../lib/chat/rules");
const { validateAction } = require("../lib/chat/actions");

const TODAY = "2026-10-21"; // Wednesday
const ctx = {
  today: TODAY,
  tasks: [
    { id: "t1", title: "Maths homework", minutes: 45, priority: "High", due: "2026-10-23", completed: false },
    { id: "t2", title: "Physics lab report", minutes: 60, completed: false },
    { id: "t3", title: "Reading", minutes: 30, completed: false },
    { id: "t4", title: "Chemistry quiz prep", minutes: 40, completed: false },
    { id: "t5", title: "Chemistry assignment", minutes: 90, completed: false },
    { id: "t6", title: "Old essay", minutes: 20, completed: true },
  ],
  habits: [{ id: "h1", title: "Coding practice", minutes: 45 }, { id: "h2", title: "Aptitude practice", minutes: 60 }],
  plan: {
    sessions: [
      { id: "s1", title: "Maths homework", kind: "TASK", refId: "t1", start: "16:00", end: "16:45", status: "PLANNED" },
      { id: "s2", title: "Physics lab report", kind: "TASK", refId: "t2", start: "17:00", end: "18:00", status: "PLANNED" },
      { id: "s3", title: "Reading", kind: "TASK", refId: "t3", start: "09:00", end: "09:30", status: "DONE" },
    ],
  },
};

const run = (text) => interpret(text, ctx);
const only = (text) => {
  const r = run(text);
  assert.equal(r.actions.length, 1, `${text} -> ${JSON.stringify(r)}`);
  return r.actions[0];
};

// ------------------------------------------------------------------- adding tasks
test("add a task with length, due date and priority", () => {
  const a = only("add task maths revision 45 min due friday high priority");
  assert.equal(a.type, "add_task");
  assert.deepEqual(a.args, { title: "Maths revision", minutes: 45, priority: "High", due: "2026-10-23" });
});

test("different ways of asking", () => {
  assert.equal(only("Add a new task: read chapter 4 for 30 minutes").args.title, "Read chapter 4");
  assert.equal(only("remind me to call the bank tomorrow").args.due, "2026-10-22");
  assert.equal(only("I need to submit the assignment by 25 oct").args.due, "2026-10-25");
  assert.equal(only("create task practice DSA 1 hour").args.minutes, 60);
  assert.equal(only("todo: buy a calculator").args.title, "Buy a calculator");
  assert.equal(only("I have to finish the lab record in 3 days").args.due, "2026-10-24");
  assert.equal(only("please add task chemistry lab urgent").args.priority, "High");
  assert.equal(only("add physics notes").args.title, "Physics notes");
});

test("an empty task asks what it is", () => {
  const r = run("add task");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /called/);
});

test("slots are removed from the title, titles keep their own words", () => {
  assert.equal(cleanTitle("  the maths   homework for  "), "Maths homework");
  const { slots, rest } = extractSlots("maths homework for 45 min due tomorrow low priority", TODAY);
  assert.deepEqual(slots, { priority: "Low", minutes: 45, due: "2026-10-22" });
  assert.equal(cleanTitle(rest), "Maths homework");
});

// --------------------------------------------------------------------- finishing things
test("finishing a task, whatever the phrasing", () => {
  for (const t of ["I finished maths homework", "mark maths homework as done", "maths homework is done", "done with maths homework", "I'm done with maths homework", "completed maths homework", "I did maths homework"]) {
    const a = only(t);
    assert.deepEqual([a.type, a.args.taskId], ["complete_task", "t1"], t);
  }
});

test("a partial name is enough when it points to one task", () => {
  assert.equal(only("mark physics done").args.taskId, "t2");
  assert.equal(only("I finished the lab report").args.taskId, "t2");
  assert.equal(only("done with reading").args.taskId, "t3");
});

test("an ambiguous name asks which one", () => {
  const r = run("mark chemistry done");
  assert.equal(r.actions.length, 0);
  assert.ok(r.clarify);
  assert.deepEqual(r.clarify.options.map((o) => o.label).sort(), ["Chemistry assignment", "Chemistry quiz prep"]);
  // choosing an option sends a sentence that now points to exactly one task
  const again = run(r.clarify.options.find((o) => o.label === "Chemistry quiz prep").text);
  assert.equal(again.actions[0].args.taskId, "t4");
});

test("a finished task that is already completed is not found again", () => {
  const r = run("I finished old essay");
  assert.equal(r.actions.length, 0);
});

test("habits can be finished too", () => {
  assert.deepEqual([only("I did coding practice").type, only("done with aptitude practice").args.habitId], ["complete_habit", "h2"]);
});

test("time spent on a task", () => {
  const a = only("I worked on maths homework for 30 minutes");
  assert.deepEqual([a.type, a.args], ["log_progress", { taskId: "t1", minutes: 30 }]);
  assert.equal(only("spent 1 hour on physics lab report").args.minutes, 60);
});

// --------------------------------------------------------------------------- deleting
test("deleting needs confirmation, and says so", () => {
  const r = run("delete reading");
  assert.equal(r.actions[0].type, "delete_task");
  assert.match(r.reply, /confirm/i);
  assert.equal(validateAction(r.actions[0]).action.confirm, true);
  assert.equal(only("remove the coding practice habit").type, "delete_habit");
});

test("removing a due date or priority is a change, never a delete", () => {
  for (const t of ["remove the due date from maths homework", "clear the deadline of reading", "remove priority from physics lab report"]) {
    const r = interpret(t, ctx);
    assert.ok(!r.actions.some((a) => a.type === "delete_task"), t);
  }
});

test("deleting something that isn't there", () => {
  const r = run("delete the swimming task");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /couldn't find/);
});

// ---------------------------------------------------------------------------- changing
test("move a planned session to a time today", () => {
  const a = only("move physics to 6pm");
  assert.deepEqual([a.type, a.args], ["move_session", { sessionId: "s2", time: "18:00", flexible: false }]);
  assert.equal(only("put maths homework at 7:30 pm").args.time, "19:30");
  assert.equal(only("schedule the lab report at 20:15").args.time, "20:15");
});

test("a part of the day is 'around then'", () => {
  const a = only("move maths homework to the evening");
  assert.deepEqual([a.args.time, a.args.flexible], ["18:00", true]);
});

test("moving to another day is explained, not guessed", () => {
  const r = run("move physics to tomorrow evening");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /due date/);
});

test("a task with no session today can't be moved", () => {
  const r = run("move reading to 5pm"); // its session today is already done
  assert.equal(r.actions.length, 0);
});

test("change the due date", () => {
  const a = only("change maths homework due date to monday");
  assert.deepEqual([a.type, a.args], ["update_task", { taskId: "t1", due: "2026-10-26" }]);
  assert.equal(only("move physics lab report to friday").args.due, "2026-10-23");
  assert.equal(only("set reading due tomorrow").args.due, "2026-10-22");
  assert.equal(only("remove the due date from maths homework").args.due, "none");
});

test("change the length and the priority", () => {
  assert.deepEqual(only("make maths homework 90 minutes").args, { taskId: "t1", minutes: 90 });
  assert.deepEqual(only("change physics lab report to 2 hours").args, { taskId: "t2", minutes: 120 });
  assert.deepEqual(only("set reading to high priority").args, { taskId: "t3", priority: "High" });
});

test("rename", () => {
  const a = only("rename reading to read chapter 5");
  assert.deepEqual(a.args, { taskId: "t3", title: "Read chapter 5" });
});

test("skip and later", () => {
  assert.deepEqual([only("skip physics").type, only("skip physics").args.sessionId], ["skip_session", "s2"]);
  assert.equal(only("do maths homework later").type, "skip_session");
});

test("re-plan", () => {
  assert.equal(only("re-plan my day").type, "replan");
  assert.equal(only("can you replan").type, "replan");
  assert.equal(only("reshuffle my schedule").type, "replan");
});

// ---------------------------------------------------------------------------- questions
test("plan, free time and lists", () => {
  assert.deepEqual(only("what's my plan today?").args, { day: "today" });
  assert.deepEqual(only("what is my plan for tomorrow").args, { day: "tomorrow" });
  assert.equal(only("show my schedule").type, "show_plan");
  assert.deepEqual(only("how much free time do I have tomorrow?").args, { day: "tomorrow" });
  assert.deepEqual(only("when am I free").args, { day: "today" });
  assert.deepEqual(only("what's overdue").args, { filter: "overdue" });
  assert.deepEqual(only("what's due this week").args, { filter: "due_soon" });
  assert.deepEqual(only("show my tasks").args, { filter: "open" });
  assert.equal(only("what did I miss today").type, "show_missed");
});

test("other days are not supported, and it says so", () => {
  const r = run("what's my plan on friday");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /today and tomorrow/);
});

// ------------------------------------------------------------------------------ habits
test("adding habits: every day, times a week, fixed days", () => {
  let a = only("add habit coding practice every day 45 min");
  assert.deepEqual(a.args, { title: "Coding practice", kind: "DAILY", minutes: 45 });
  a = only("new habit aptitude twice a week 1 hour");
  assert.deepEqual(a.args, { title: "Aptitude", kind: "WEEKLY", perWeek: 2, minutes: 60 });
  a = only("add habit gym on mon wed fri 1 hour");
  assert.deepEqual(a.args, { title: "Gym", kind: "DAYS", days: [1, 3, 5], minutes: 60 });
  assert.deepEqual(only("add a habit yoga on weekends").args.days, [6, 7]);
  assert.equal(only("add habit revision 3 times a week").args.perWeek, 3);
});

test("a habit without how often asks", () => {
  const r = run("add habit meditation");
  assert.equal(r.actions.length, 0);
  assert.equal(r.clarify.options.length, 3);
});

// ----------------------------------------------------------------------- talk and edges
test("help, greetings, thanks, nonsense", () => {
  assert.match(run("help").reply, /add task/);
  assert.match(run("hi").reply, /Hi/);
  assert.match(run("thanks!").reply, /welcome/);
  const r = run("blah blah banana");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /not sure/);
  assert.match(run("").reply, /Type a message/);
});

test("every action it produces passes the strict check", () => {
  const phrases = [
    "add task maths revision 45 min due friday high priority", "remind me to call tomorrow", "I finished maths homework",
    "delete reading", "move physics to 6pm", "change maths homework due date to monday", "make maths homework 90 minutes",
    "rename reading to read chapter 5", "skip physics", "re-plan my day", "what's my plan today", "what's overdue",
    "add habit gym on mon wed fri 1 hour", "I did coding practice", "I worked on maths homework for 30 minutes",
  ];
  for (const p of phrases) {
    for (const a of run(p).actions) {
      const v = validateAction(a);
      assert.ok(v.action, `${p} -> ${JSON.stringify(a)} -> ${v.error}`);
    }
  }
});

test("a missing or odd context never crashes it", () => {
  assert.doesNotThrow(() => interpret("what's my plan today", undefined));
  assert.doesNotThrow(() => interpret("mark maths done", { tasks: [null, { id: "x" }, { title: "y" }] }));
  assert.doesNotThrow(() => interpret("x".repeat(5000), ctx));
});
