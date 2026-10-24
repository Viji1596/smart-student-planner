const test = require("node:test");
const assert = require("node:assert/strict");
const { interpret } = require("../lib/chat/rules");
const { detect, CRISIS_REPLY } = require("../lib/chat/support");

const TODAY = "2026-10-21";
const ctx = {
  today: TODAY,
  nowMin: 15 * 60,
  tasks: [
    { id: "t1", title: "Maths homework", minutes: 45, priority: "High", due: "2026-10-20", completed: false },
    { id: "t2", title: "Physics lab report", minutes: 60, due: "2026-10-23", completed: false },
    { id: "t3", title: "Reading", minutes: 30, completed: false },
    { id: "t4", title: "Stress management notes", minutes: 20, completed: false },
  ],
  habits: [],
  plan: { sessions: [
    { id: "s1", title: "Maths homework", kind: "TASK", refId: "t1", start: "16:00", end: "16:45", status: "PLANNED" },
    { id: "s2", title: "Physics lab report", kind: "TASK", refId: "t2", start: "17:00", end: "18:00", status: "PLANNED" },
  ], busy: [], freeBlocks: [] },
};
const run = (text, history = []) => interpret(text, ctx, history);

// ----------------------------------------------------------------- crisis: always a person, never a plan
test("a message about self-harm gets care and real help, nothing else", () => {
  for (const t of ["I want to kill myself", "i don't want to live anymore", "I want to die", "thinking about suicide", "I feel like hurting myself", "everyone would be better off without me", "I can't go on"]) {
    const r = run(t);
    assert.equal(r.actions.length, 0, t);
    assert.equal(r.clarify, null, t);
    assert.match(r.reply, /14416/, t);
    assert.match(r.reply, /112/, t);
    assert.match(r.reply, /Tele-MANAS/, t);
    assert.match(r.reply, /carry this alone/, t);
  }
});

test("the crisis answer does not push tasks, plans or productivity", () => {
  assert.doesNotMatch(CRISIS_REPLY, /focus|lighten|re-?plan|what should i|show (me )?my|report/i);
  assert.match(CRISIS_REPLY, /can wait/);
});

test("a crisis is recognised even inside a command-shaped sentence", () => {
  const r = run("add task: I want to end my life");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /14416/);
});

test("ordinary tiredness or stress words are not a crisis", () => {
  for (const t of ["I'm so tired", "I'm stressed about the exam", "this is killing me, so much work"]) {
    assert.doesNotMatch(run(t).reply, /14416/, t);
  }
});

// -------------------------------------------------------------------------------- overwhelmed
test("overwhelmed: warm, concrete, and offers to make the day smaller", () => {
  for (const t of ["I'm so overwhelmed", "I feel swamped", "I can't cope with all this", "too much to do", "I don't know where to start", "I'm completely burnt out", "I'm stressed out"]) {
    const r = run(t);
    assert.equal(detect(t.toLowerCase()), "overwhelmed", t);
    assert.deepEqual(r.actions.map((a) => a.type), ["show_focus"], t);
    assert.ok(r.clarify.options.some((o) => o.text === "lighten my day"), t);
    assert.match(r.reply, /overwhelm|a lot|pile|smaller|deadlines/i, t);
  }
});

test("it uses what it knows about the load", () => {
  const r = run("I'm overwhelmed");
  assert.match(r.reply, /4 open tasks \(1 overdue\)/);
  assert.match(r.reply, /1 h 45 min planned/);
});

test("each chip offered when overwhelmed leads somewhere useful", () => {
  const options = run("I'm overwhelmed").clarify.options;
  assert.equal(run(options[0].text).actions[0].type, "lighten_day");
  assert.match(run(options[1].text).reply, /break|water/i);
  assert.match(run(options[2].text).reply, /listening/);
});

test("a task whose name contains a feeling word is still just a command", () => {
  const r = run("add task stress test report 30 min");
  assert.equal(r.actions[0].type, "add_task");
  assert.match(r.actions[0].args.title, /Stress test report/);
  assert.equal(run("mark stress management notes done").actions[0].type, "complete_task");
});

// --------------------------------------------------------------- low, tired, exams, wins, talk
test("low motivation gets the 10-minute trick and the focus list", () => {
  const r = run("I have no motivation");
  assert.deepEqual(r.actions.map((a) => a.type), ["show_focus"]);
  assert.match(r.reply, /10-minute|10 minutes/);
  assert.equal(run("I keep procrastinating").actions[0].type, "show_focus");
});

test("tired: rest first, and it offers to ease the day", () => {
  const r = run("I'm so tired today");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /break|rest/i);
  assert.ok(r.clarify.options.some((o) => o.text === "lighten my day"));
});

test("exam nerves", () => {
  const r = run("I'm scared about my exam");
  assert.equal(r.actions[0].type, "show_focus");
  assert.match(r.reply, /exam/i);
});

test("good news is celebrated", () => {
  const r = run("I crushed it today");
  assert.match(r.reply, /great|love/i);
  assert.equal(r.clarify.options[0].text, "how am I doing");
});

test("'I just want to talk' gets a listener, not a plan", () => {
  const r = run("I just want to talk");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /listening/);
});

test("it varies its words but the same message always gets the same answer", () => {
  assert.equal(run("I'm overwhelmed").reply, run("I'm overwhelmed").reply);
  const openers = new Set(["I'm overwhelmed", "I feel swamped", "too much to do", "I can't cope", "I'm burnt out", "so stressed"].map((t) => run(t).reply.split("\n")[0]));
  assert.ok(openers.size >= 2, "more than one opening line is used");
});

// --------------------------------------------------------------------------------- the reports
test("asking how you're doing shows the report", () => {
  for (const [t, days] of [["how am I doing?", 7], ["give me my weekly report", 7], ["how was my week", 7], ["show my progress for the last 14 days", 14], ["performance report for the month", 30], ["how are my habits going", 7], ["my streaks", 7]]) {
    const r = run(t);
    assert.deepEqual([r.actions[0].type, r.actions[0].args.days], ["show_report", days], t);
  }
});

test("a task named 'report' is not mistaken for the performance report", () => {
  const r = run("move physics lab report to friday");
  assert.equal(r.actions[0].type, "defer_task");
  assert.equal(run("mark physics lab report done").actions[0].type, "complete_task");
});

// --------------------------------------------------------------------- focus and lightening
test("what to focus on", () => {
  for (const t of ["what should I focus on", "where do I start", "what's the most important thing", "help me prioritise", "what should I do first", "what now"]) {
    assert.equal(run(t).actions[0].type, "show_focus", t);
  }
});

test("lightening the day, with or without a budget", () => {
  assert.deepEqual(run("lighten my day").actions[0], { type: "lighten_day", args: {} });
  assert.deepEqual(run("I only have 2 hours today").actions[0].args, { keepMinutes: 120 });
  assert.equal(run("make today easier").actions[0].type, "lighten_day");
  // "too much to do" is also a feeling: the answer is caring, and "lighten my day" is one tap away
  assert.ok(run("there's too much to do today").clarify.options.some((o) => o.text === "lighten my day"));
});

// ---------------------------------------------------------------------- answers from the data
test("questions the app can answer from what it sent", () => {
  assert.match(run("what's next").reply, /Maths homework.*16:00 to 16:45/);
  assert.match(run("how many tasks do I have").reply, /4 open tasks, 1 of them overdue/);
  assert.match(run("when is maths homework due").reply, /due Tue 20 Oct/);
  assert.match(run("when is reading due").reply, /no due date/);
  assert.match(run("how long is physics lab report").reply, /1 h/);
});

// ---------------------------------------------------------------------------------- small talk
test("greetings use the plan", () => {
  const r = run("good morning");
  assert.match(r.reply, /Good morning/);
  assert.match(r.reply, /Next up is “Maths homework” at 16:00/);
  assert.equal(r.clarify.options.length, 3);
});

test("who are you, how are you, jokes, goodnight", () => {
  assert.match(run("who are you").reply, /not a person and not a therapist/);
  assert.match(run("how are you").reply, /how are you/i);
  assert.ok(run("tell me a joke").reply.length > 20);
  assert.match(run("good night").reply, /night|later|care/i);
  assert.equal(run("motivate me").actions[0].type, "show_focus");
});

test("the fallback is friendly and points to what it can do", () => {
  const r = run("zxqv blorp");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /add task/);
  assert.match(r.reply, /feeling/);
});

test("feelings are recognised after a filler too", () => {
  assert.deepEqual(run("ugh i'm so overwhelmed with everything").actions.map((a) => a.type), ["show_focus"]);
  assert.deepEqual(run("so I'm completely burnt out").actions.map((a) => a.type), ["show_focus"]);
  assert.match(run("ok i want to kill myself").reply, /14416/);
});

