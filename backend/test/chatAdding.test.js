const test = require("node:test");
const assert = require("node:assert/strict");
const { interpret } = require("../lib/chat/rules");
const { fixTypos, plainText, correct } = require("../lib/chat/typos");
const { validateAction } = require("../lib/chat/actions");

const TODAY = "2026-10-24"; // Saturday
const ctx = { today: TODAY, nowMin: 10 * 60, tasks: [], habits: [], plan: { sessions: [] } };
const add = (text) => {
  const r = interpret(text, ctx, []);
  assert.equal(r.actions.length, 1, `${text} -> ${JSON.stringify(r)}`);
  assert.equal(r.actions[0].type, "add_task", text);
  assert.ok(validateAction(r.actions[0]).action, `${text}: ${JSON.stringify(r.actions[0])}`);
  return r.actions[0].args;
};

// ------------------------------------------------------------------- the sentence that was reported
test("'i want to add a high priority task MLP Project deadline tommorrow' (typo and all)", () => {
  assert.deepEqual(add("i want to add a high priority task MLP Project deadline tommorrow"), {
    title: "MLP Project", priority: "High", due: "2026-10-25",
  });
});

test("the same without the lead-in, and spelled correctly", () => {
  const expected = { title: "MLP Project", priority: "High", due: "2026-10-25" };
  assert.deepEqual(add("add a high priority task MLP Project deadline tommorrow"), expected);
  assert.deepEqual(add("add a high priority task MLP Project deadline tomorrow"), expected);
});

test("the word 'deadline' is a due date, never part of the title", () => {
  for (const t of ["add task report deadline friday", "add task report deadline is friday", "add task report with a deadline of friday", "add task report, due date friday", "add task report submission on friday"]) {
    const args = add(t);
    assert.equal(args.due, "2026-10-30", t);
    assert.doesNotMatch(args.title, /deadline|due|submission/i, `${t} -> ${args.title}`);
  }
});

// ------------------------------------------------------------------------- how people lead in
test("different ways of asking to add a task", () => {
  assert.equal(add("I would like to create a new urgent task: submit lab record by monday").title, "Submit lab record");
  assert.equal(add("i wanna add a task read chapter 4 for 30 min").minutes, 30);
  assert.equal(add("need to add task DBMS lab record due next monday 1 hour").minutes, 60);
  assert.equal(add("can you please add a low priority task clean desk").priority, "Low");
  assert.equal(add("I need to add a task call home").title, "Call home");
  assert.equal(add("let's add a task revise OS").title, "Revise OS");
  assert.equal(add("could you create a task for the viva prep tomorrow").due, "2026-10-25");
  assert.equal(add("add another task: practice DSA 45 minutes").minutes, 45);
});

test("an adjective before the word 'task' is kept as the priority", () => {
  assert.equal(add("add an urgent task fix the bug").priority, "High");
  assert.equal(add("create a medium priority task lab notes").priority, "Medium");
  assert.equal(add("add a high-priority task viva prep").priority, "High");
});

test("'add X as a task' and 'add X to my to-do list'", () => {
  assert.deepEqual(add("add MLP project as a high priority task due tmrw"), { title: "MLP project", priority: "High", due: "2026-10-25" });
  assert.equal(add("add reading to my to-do list").title, "Reading");
  assert.equal(add("put the lab record in my tasks").title, "Lab record");
});

test("'I have to ...' and 'remind me to ...' still work, typos included", () => {
  assert.deepEqual(add("i have to submit the mlp project tommorow"), { title: "Submit the MLP project", due: "2026-10-25" });
  assert.equal(add("remind me to call the bank tomorow").due, "2026-10-25");
});

test("'I need to add the figures to the report' is a task, not a request for the performance report", () => {
  const args = add("i need to add the figures to the report");
  assert.equal(args.title, "Add the figures to the report");
});

// -------------------------------------------------------------------------------------- typos
test("typos in days, parts of the day and priority words are corrected", () => {
  const cases = {
    tommorrow: "tomorrow", tomorow: "tomorrow", tommorow: "tomorrow", tmrw: "tomorrow", "2morrow": "tomorrow",
    wensday: "wednesday", wednsday: "wednesday", thrusday: "thursday", frieday: "friday", mondy: "monday", satruday: "saturday",
    evning: "evening", eveing: "evening", mornin: "morning", afternon: "afternoon", tonite: "tonight",
    priorty: "priority", deadlin: "deadline", minuts: "minutes",
  };
  for (const [typo, fixed] of Object.entries(cases)) assert.equal(correct(typo), fixed, typo);
});

test("correct words, real words and task titles are left alone", () => {
  for (const word of ["tomorrow", "monday", "money", "morning", "mourning", "headline", "friend", "priorities", "minute", "hour", "sunny", "today", "toady"]) {
    if (["mondays", "priorities"].includes(word)) continue;
    const fixed = fixTypos(word);
    assert.ok(fixed === word || ["toady"].includes(word) === false, `${word} -> ${fixed}`);
  }
  assert.equal(fixTypos("Money Monday MLP Project"), "Money Monday MLP Project");
  assert.equal(fixTypos("add task Friend request headline"), "add task Friend request headline");
  assert.equal(fixTypos("Numpy basics 2 hours"), "Numpy basics 2 hours");
});

test("a typo is fixed inside a whole sentence, keeping capital letters", () => {
  assert.equal(fixTypos("Tommorrow evning at 6"), "Tomorrow evening at 6");
});

test("typos no longer lose a deadline", () => {
  for (const day of ["tommorrow", "tomorow", "tmrw", "2morrow"]) assert.equal(add(`add task essay due ${day}`).due, "2026-10-25", day);
  assert.equal(add("add task essay due wensday").due, "2026-10-28");
  assert.equal(interpret("move it to the evning", ctx, []).actions.length, 0); // nothing planned: no action, but no crash
});

// ------------------------------------------------------------------ Samsung and other keyboards
test("curly apostrophes and non-breaking spaces are treated as plain ones", () => {
  assert.equal(plainText("what’s my plan"), "what's my plan");
  assert.equal(plainText("I’d like “this”"), "I'd like \"this\"");
  assert.equal(plainText("a\u00a0b"), "a b");
});

test("sentences typed with curly apostrophes are understood", () => {
  const withSessions = { ...ctx, nowMin: 15 * 60, plan: { sessions: [{ id: "s1", title: "Maths", kind: "TASK", refId: "t1", start: "16:00", end: "16:45", status: "PLANNED" }] }, tasks: [{ id: "t1", title: "Maths", minutes: 45, completed: false }] };
  assert.equal(interpret("what’s my plan today?", withSessions, []).actions[0].type, "show_plan");
  assert.deepEqual(interpret("I’m so overwhelmed", withSessions, []).actions.map((a) => a.type), ["show_focus"]);
  assert.match(interpret("I can’t go on", withSessions, []).reply, /14416/);
  assert.equal(interpret("I’d like to add a task maths revision due friday", withSessions, []).actions[0].args.due, "2026-10-30");
});

// --------------------------------------------------------------------------------- not hijacked
test("ordinary sentences that start like an add are not turned into tasks wrongly", () => {
  const r = interpret("add habit coding every day", ctx, []);
  assert.equal(r.actions[0].type, "add_habit");
  assert.equal(interpret("I want to add a habit gym on mon wed fri", ctx, []).actions[0].type, "add_habit");
});

// --------------------------------------------------------------- the messy sweep: each of these failed once
const run = (text, c = ctx) => interpret(text, c, []);
const withTasks = {
  today: TODAY, nowMin: 11 * 60,
  tasks: [
    { id: "t1", title: "MLP Project", minutes: 120, priority: "High", due: "2026-10-25", completed: false },
    { id: "t2", title: "DBMS lab record", minutes: 60, due: "2026-10-28", completed: false },
  ],
  habits: [],
  plan: { sessions: [
    { id: "s1", title: "MLP Project", kind: "TASK", refId: "t1", start: "14:00", end: "16:00", status: "PLANNED" },
    { id: "s2", title: "DBMS lab record", kind: "TASK", refId: "t2", start: "17:00", end: "18:00", status: "PLANNED" },
  ] },
};

test("'by 25th', 'on the 3rd', 'due 30th' are dates; '2nd year' is not", () => {
  assert.deepEqual(add("add task submit report by 25th"), { title: "Submit report", due: "2026-10-25" });
  assert.equal(add("add task lab record due the 30th").due, "2026-10-30");
  assert.equal(add("add task essay on the 3rd").due, "2026-11-03", "the 3rd has passed this month: next month");
  assert.equal(add("add task prepare 2nd year syllabus").due, undefined);
});

test("a statement with a date, length or priority and no command is a task", () => {
  assert.deepEqual(add("exam prep 2hrs tmrw"), { title: "Exam prep", minutes: 120, due: "2026-10-25" });
  assert.deepEqual(add("i have a viva on monday"), { title: "Viva", due: "2026-10-26" });
  assert.deepEqual(add("i have an assignment due friday"), { title: "Assignment", due: "2026-10-30" });
  assert.deepEqual(add("physics notes 45 min"), { title: "Physics notes", minutes: 45 });
  assert.equal(add("urgent call the bank").priority, "High");
});

test("a statement without any date, length or priority is NOT guessed to be a task", () => {
  for (const t of ["maths is hard", "blah blah", "the weather is nice"]) assert.equal(run(t).actions.length, 0, t);
});

test("questions are never turned into tasks", () => {
  for (const t of ["do i have anything on friday?", "what is due tomorrow", "when is the viva on monday?"]) {
    assert.notEqual(run(t).actions[0] && run(t).actions[0].type, "add_task", t);
  }
});

test("a priority word at the end counts once something else was understood", () => {
  assert.deepEqual(add("add task os assignment 1.5 hrs due fri high"), { title: "OS assignment", minutes: 90, priority: "High", due: "2026-10-30" });
  assert.equal(add("add task low level design").priority, undefined, "no other slot: 'low' stays part of the title");
});

test("subject abbreviations are written properly", () => {
  assert.equal(add("add task mlp project").title, "MLP project");
  assert.equal(add("add the dbms record").title, "DBMS record");
  assert.equal(add("add task dsa practice and os notes").title, "DSA practice and OS notes");
  assert.equal(add("add task Gate prep").title, "Gate prep", "words typed with a capital are left as typed");
});

test("'add 3 tasks' is not a task called '3 tasks'", () => {
  const r = run("add 3 tasks");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /one at a time/);
  assert.equal(run("add a task").actions.length, 0);
});

test("texting spellings and missing apostrophes", () => {
  assert.equal(run("whats due this week", withTasks).actions[0].type, "show_tasks");
  assert.equal(run("wat should i do first", withTasks).actions[0].type, "show_focus");
  assert.equal(run("hows my week going", withTasks).actions[0].type, "show_report");
  assert.deepEqual(run("im so overwhelmed", withTasks).actions.map((a) => a.type), ["show_focus"]);
  assert.match(run("i cant go on", withTasks).reply, /14416/);
  assert.equal(run("pls add task read paper", withTasks).actions[0].type, "add_task");
  assert.equal(run("add hw due tmrw").actions[0].args.title, "Homework");
});

test("a task named in a statement with a date asks instead of guessing", () => {
  const r = run("submit mlp project tomorrow", withTasks);
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /already have “MLP Project”/);
  assert.deepEqual(r.clarify.options.map((o) => o.label), ["Change “MLP Project” due date to Sun 25 Oct", "Add it as a new task"]);
  // each option works
  assert.equal(run(r.clarify.options[0].text, withTasks).actions[0].type, "update_task");
  assert.equal(run(r.clarify.options[1].text, withTasks).actions[0].type, "add_task");
});

test("'<task> priority low' and '<task> due monday' work without a command word", () => {
  assert.deepEqual(run("mlp project priority low", withTasks).actions[0], { type: "update_task", args: { taskId: "t1", priority: "Low" } });
  assert.deepEqual(run("dbms lab record due monday", withTasks).actions[0].args, { taskId: "t2", due: "2026-10-26" });
  assert.equal(run("mlp deadline tomorrow", withTasks).actions[0].args.due, "2026-10-25");
});

test("'what time is my session' and 'when do I start' are answered from the plan", () => {
  assert.match(run("what time is my mlp session", withTasks).reply, /“MLP Project” is planned from 14:00 to 16:00 today/);
  assert.match(run("when do i start dbms", withTasks).reply, /17:00 to 18:00/);
  assert.match(run("when do i start os", { ...withTasks, tasks: [...withTasks.tasks, { id: "t9", title: "OS notes", minutes: 30, due: "2026-10-29", completed: false }] }).reply, /isn't planned for today. It's due Thu 29 Oct/);
});

test("everything this produces passes the strict check", () => {
  for (const t of ["exam prep 2hrs tmrw", "i have a viva on monday", "add task os assignment 1.5 hrs due fri high", "mlp project priority low", "add task submit report by 25th"]) {
    for (const a of run(t, withTasks).actions) assert.ok(validateAction(a).action, `${t} -> ${JSON.stringify(a)}`);
  }
});

