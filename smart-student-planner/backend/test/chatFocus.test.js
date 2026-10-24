const test = require("node:test");
const assert = require("node:assert/strict");
const { interpret } = require("../lib/chat/rules");

const ctx = {
  today: "2026-10-24",
  nowMin: 13 * 60 + 20,
  tasks: [
    { id: "t1", title: "Integration 7.5", minutes: 80, priority: "High", completed: false },
    { id: "t2", title: "Physics lab", minutes: 60, completed: false },
    { id: "t3", title: "DSA practice", minutes: 45, completed: false },
  ],
  habits: [],
  plan: { sessions: [{ id: "s1", title: "Physics lab", kind: "TASK", refId: "t2", start: "15:00", end: "16:00", status: "PLANNED" }], busy: [], freeBlocks: [] },
};
const run = (t) => interpret(t, ctx, []);
const types = (t) => run(t).actions.map((a) => a.type).join("+");

test("'what to focus on', and the many other ways of asking it", () => {
  const phrases = [
    "what to focus on", "What to focus on?", "what do i focus on", "what should i focus on", "what should i be focusing on", "focus",
    "what to do", "what to do now", "what should i do", "what should i do today", "what do i do now", "what can i do now",
    "what do i work on", "what should i work on first", "where to start", "where do i begin", "where should i start",
    "which task first", "which one should i start with", "what's my priority", "what are my priorities", "priorities",
    "most urgent", "what's urgent", "what's important", "what is the most important task", "tell me what to do", "suggest what to do",
    "what do i do next", "i don't know what to do", "i dont know where to start", "what should i study", "what should i study now",
    "what's first", "start with what", "give me something to do", "what's on my plate", "what needs my attention", "what to start with",
    "what to study", "what do i need to do first", "what do i tackle first", "help me focus", "help me prioritise", "what first",
    "what next", "what now", "so what should i focus on", "ok what to focus on", "hey what should i do",
  ];
  const bad = phrases.filter((p) => types(p) !== "show_focus");
  assert.deepEqual(bad, []);
});

test("typos and a Samsung keyboard don't get in the way", () => {
  for (const t of ["wat to focus on", "what to focs on", "what shud i focus on", "whats my priorty", "what should i focus on?!", "what should i focus on…", "what should i focus on ?"]) {
    assert.equal(types(t), "show_focus", t);
  }
});

test("'what's next' still shows the next session (it is a different question)", () => {
  assert.equal(types("what's next"), "");
  assert.match(run("what's next").reply, /Next up/);
});

test("commands that mention focus are still commands", () => {
  assert.equal(types("add task focus on dsa due friday"), "add_task");
  assert.equal(run("add task focus on dsa due friday").actions[0].args.title, "Focus on DSA");
  assert.equal(types("add a task priorities review"), "add_task");
  assert.equal(types("mark dsa practice done"), "complete_task");
  assert.equal(types("delete the priorities task"), "");
  assert.equal(types("start dsa practice"), "start_task");
});

test("things said about themselves are not questions", () => {
  assert.notEqual(types("i can't focus"), "show_focus+show_focus"); // may be comforted, never treated as a task
  assert.ok(!/add_task/.test(types("i can't focus")));
  assert.ok(!/add_task/.test(types("my priority is physics")));
});

test("a question about one of their tasks is about that task", () => {
  assert.notEqual(types("what should i do about physics lab"), "show_focus");
  assert.notEqual(types("what to do with dsa practice"), "show_focus");
});

test("about another day it is a question about that day's plan", () => {
  assert.notEqual(types("what should i do tomorrow"), "show_focus");
  assert.notEqual(types("what to do on friday"), "show_focus");
});

test("feeling overwhelmed still leads to the focus list, with kindness first", () => {
  const r = run("i feel overwhelmed");
  assert.ok(r.actions.some((a) => a.type === "show_focus"));
  assert.ok(r.reply.length > 40);
});

test("a recommendation reply, then a follow-up question, still works", () => {
  const first = interpret("what to focus on", ctx, []);
  const second = interpret("what should i focus on", ctx, [{ role: "user", text: "what to focus on" }, { role: "assistant", text: first.reply }]);
  assert.equal(second.actions[0].type, "show_focus");
});
