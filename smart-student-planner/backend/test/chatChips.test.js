// Every button ("chip") the assistant offers sends a message. This checks that tapping any of them
// gets a real answer, never "I didn't quite get that".
const test = require("node:test");
const assert = require("node:assert/strict");
const { interpret } = require("../lib/chat/rules");

const ctx = {
  today: "2026-10-24",
  nowMin: 11 * 60,
  tasks: [
    { id: "t1", title: "MLP Project", minutes: 120, priority: "High", due: "2026-10-25", completed: false },
    { id: "t2", title: "DBMS lab record", minutes: 60, due: "2026-10-28", completed: false },
    { id: "t3", title: "Chemistry quiz prep", minutes: 40, completed: false },
    { id: "t4", title: "Chemistry assignment", minutes: 90, completed: false },
    { id: "t5", title: "Reading", minutes: 30, completed: false },
  ],
  habits: [{ id: "h1", title: "Coding practice", minutes: 45, kind: "DAILY" }],
  plan: {
    sessions: [
      { id: "s1", title: "MLP Project", kind: "TASK", refId: "t1", start: "14:00", end: "16:00", status: "PLANNED" },
      { id: "s2", title: "DBMS lab record", kind: "TASK", refId: "t2", start: "17:00", end: "18:00", status: "PLANNED" },
      { id: "s3", title: "Reading", kind: "TASK", refId: "t5", start: "19:00", end: "19:30", status: "PLANNED" },
    ],
    busy: [{ label: "Dinner", kind: "MEAL", start: "20:00", end: "20:40" }],
    freeBlocks: [{ label: "free", start: "18:00", end: "19:00" }, { label: "free", start: "20:40", end: "22:00" }],
  },
};
const NOT_UNDERSTOOD = /not sure I caught|didn't quite get/;

// messages that lead to buttons of every kind
const STARTERS = [
  "hi", "good morning", "I'm overwhelmed", "I have no motivation", "I'm so tired", "I'm scared about my exam", "I crushed it today",
  "I just want to talk", "I need a break", "mark chemistry done", "delete chemistry", "move reading", "move reading to friday",
  "move mlp project to monday", "change mlp project to friday", "submit mlp project tomorrow", "add habit meditation",
  "postpone mlp project to tuesday", "I only have 2 hours today", "lighten my day", "what should I focus on", "how am I doing",
  "move it", "earlier",
];

test("every button, when tapped, leads somewhere useful", () => {
  let checked = 0;
  for (const message of STARTERS) {
    const first = interpret(message, ctx, []);
    for (const option of (first.clarify && first.clarify.options) || []) {
      const second = interpret(option.text, ctx, [{ role: "user", text: message }, { role: "assistant", text: first.reply }]);
      assert.doesNotMatch(second.reply, NOT_UNDERSTOOD, `"${message}" -> button "${option.label}" (${option.text}) -> ${second.reply.slice(0, 80)}`);
      assert.ok(second.actions.length > 0 || second.reply.length > 5, `${option.text} gives an answer`);
      checked++;
    }
  }
  assert.ok(checked >= 20, `${checked} buttons were checked`);
});

test("the buttons that lead to a change really produce one", () => {
  const due = interpret("move mlp project to monday", ctx, []);
  assert.equal(due.actions[0].type, "defer_task");
  const late = interpret("move dbms lab record to saturday", ctx, []); // due Wednesday 28th: that would be late
  // saturday 2026-10-24 is today: it's already planned, so there is nothing to warn about
  assert.equal(late.actions.length, 0);
  const warned = interpret("postpone dbms lab record to friday", ctx, []);
  assert.equal(warned.actions[0].type, "defer_task");
  assert.match(warned.reply, /due Wed 28 Oct, so that would be late/);
  const change = interpret(warned.clarify.options[0].text, ctx, []);
  assert.deepEqual(change.actions[0], { type: "update_task", args: { taskId: "t2", due: "2026-10-30" } });
});
