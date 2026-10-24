const test = require("node:test");
const assert = require("node:assert/strict");
const { interpret } = require("../lib/chat/rules");
const { validateAction } = require("../lib/chat/actions");

const TODAY = "2026-10-21"; // Wednesday
const ctx = {
  today: TODAY,
  nowMin: 10 * 60, // 10:00
  tasks: [
    { id: "t1", title: "Maths homework", minutes: 45, priority: "High", due: "2026-10-22", completed: false },
    { id: "t2", title: "Physics lab report", minutes: 60, due: "2026-10-30", completed: false },
    { id: "t3", title: "Reading", minutes: 30, completed: false },
  ],
  habits: [{ id: "h1", title: "Coding practice", minutes: 45, kind: "DAILY", doneToday: false }],
  plan: {
    sessions: [
      { id: "s1", title: "Maths homework", kind: "TASK", refId: "t1", start: "16:00", end: "16:45", status: "PLANNED" },
      { id: "s2", title: "Physics lab report", kind: "TASK", refId: "t2", start: "17:00", end: "18:00", status: "PLANNED" },
      { id: "s3", title: "Reading", kind: "TASK", refId: "t3", start: "19:00", end: "19:30", status: "PLANNED" },
      { id: "s4", title: "Coding practice", kind: "HABIT", refId: "h1", start: "21:00", end: "21:45", status: "PLANNED" },
    ],
    busy: [
      { label: "Dinner", kind: "MEAL", start: "20:00", end: "20:40" },
      { label: "Maths class", kind: "CLASS", start: "09:00", end: "11:00" },
      { label: "Physics class", kind: "CLASS", start: "13:00", end: "14:00" },
    ],
    freeBlocks: [{ label: "free", start: "18:00", end: "19:00" }, { label: "free", start: "20:40", end: "21:00" }],
  },
};
const run = (text, history = []) => interpret(text, ctx, history);
const one = (text, history = []) => {
  const r = run(text, history);
  assert.equal(r.actions.length, 1, `${text} -> ${JSON.stringify(r)}`);
  return r.actions[0];
};

// ----------------------------------------------------------------- the phrasings that used to fail
test("moving to a time, written the way people write it", () => {
  assert.deepEqual(one("move physics to 6pm").args, { sessionId: "s2", time: "18:00", flexible: false });
  assert.deepEqual(one("shift maths homework to 6").args, { sessionId: "s1", time: "18:00", flexible: false }, "a bare 6 at 10:00 is 18:00");
  assert.equal(one("move maths homework to 6 o clock").args.time, "18:00");
  assert.equal(one("reschedule reading for 7:30").args.time, "19:30", "7:30 means the evening one, not 07:30");
  assert.equal(one("move physics to the 7pm slot").args.time, "19:00");
  assert.equal(one("do physics at 6").args.time, "18:00");
  assert.equal(one("can you please move reading to 18:45").args.time, "18:45");
});

test("a session can be named by its time or its place in the day", () => {
  assert.equal(one("move the 4pm session to 8pm").args.sessionId, "s1");
  assert.equal(one("move the 5 pm one to 6").args.sessionId, "s2");
  assert.equal(one("can you shift my first session to 8 pm").args.sessionId, "s1");
  assert.equal(one("move the last session to 7pm").args.sessionId, "s4");
  assert.equal(one("move the second session to 8pm").args.sessionId, "s2");
  assert.equal(one("move my next session to 7pm").args.sessionId, "s1");
});

test("after and before another session or a meal", () => {
  assert.deepEqual(one("move reading after dinner").args, { sessionId: "s3", time: "20:40", flexible: true });
  assert.deepEqual(one("put reading after physics").args, { sessionId: "s3", time: "18:00", flexible: true });
  // before: starts early enough to finish before the other starts, with a 5 minute gap
  assert.deepEqual(one("shift reading before physics").args, { sessionId: "s3", time: "16:25", flexible: false });
  assert.equal(one("move maths homework before dinner").args.time, "19:10");
});

test("a part of the day, now, later", () => {
  assert.deepEqual(one("move maths homework to the evening").args, { sessionId: "s1", time: "18:00", flexible: true });
  assert.equal(one("move reading to tonight").args.time, "20:00");
  assert.equal(one("move reading to now").args.time, "10:00");
  assert.equal(one("move maths homework to later").type, "skip_session");
});

test("swapping two sessions", () => {
  const a = one("swap maths homework and reading");
  assert.deepEqual([a.type, a.args], ["swap_sessions", { sessionA: "s1", sessionB: "s3" }]);
  assert.equal(one("switch the first and the last session").args.sessionB, "s4");
  assert.equal(one("put reading in the slot of maths homework").type, "swap_sessions");
  assert.equal(one("move physics instead of reading").type, "swap_sessions");
});

test("swapping a session with itself is caught", () => {
  const r = run("swap reading and reading");
  assert.equal(r.actions.length, 0);
});

test("another day means putting the task off, and the due date is not touched", () => {
  assert.deepEqual(one("move maths to tomorrow").args, { taskId: "t1", until: "2026-10-22" });
  assert.deepEqual(one("move physics lab report to friday").args, { taskId: "t2", until: "2026-10-23" });
  assert.deepEqual(one("push reading to next week").args, { taskId: "t3", until: "2026-10-26" });
  assert.deepEqual(one("do maths homework tomorrow").args, { taskId: "t1", until: "2026-10-22" });
  assert.deepEqual(one("postpone reading").args, { taskId: "t3", until: "2026-10-22" });
  assert.deepEqual(one("postpone reading to friday").args, { taskId: "t3", until: "2026-10-23" });
  assert.deepEqual(one("I can't do reading today").args, { taskId: "t3", until: "2026-10-22" });
  for (const phrase of ["move maths to tomorrow", "postpone reading"]) {
    assert.ok(!run(phrase).actions.some((a) => a.type === "update_task"), phrase);
  }
});

test("putting a task off past its due date is warned about, and offered a due-date change", () => {
  const r = run("move maths homework to friday"); // due Thursday
  assert.equal(r.actions[0].type, "defer_task");
  assert.match(r.reply, /due Thu 22 Oct, so that would be late/);
  assert.ok(r.clarify.options.some((o) => /due date/.test(o.text)));
  assert.equal(run("move reading to friday").clarify, null, "no warning when it isn't late");
});

test("a habit can't be moved to another day, but it can be skipped", () => {
  const r = run("move coding practice to tomorrow");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /skip/i);
});

test("bringing a task back", () => {
  assert.deepEqual(one("start maths homework today").args, { taskId: "t1" });
  assert.equal(one("bring back reading").type, "start_task");
  assert.equal(one("do reading today").type, "start_task");
});

test("moving something to today that is already planned today says so", () => {
  const r = run("move reading to today");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /already planned for today, at 19:00/);
});

test("'change maths to friday' asks whether that means the due date or just when to do it", () => {
  const r = run("change maths homework to friday");
  assert.equal(r.actions.length, 0);
  assert.equal(r.clarify.options.length, 2);
  assert.match(r.clarify.options[1].text, /^postpone/);
});

// ------------------------------------------------------------- remembering what was just said
test("'it' means what you were just talking about", () => {
  const history = [
    { role: "user", text: "what's my plan today" },
    { role: "assistant", text: "Your plan for today:\n17:00–18:00  Physics lab report" },
    { role: "user", text: "can you check reading" },
    { role: "assistant", text: "Reading is planned at 19:00." },
  ];
  assert.deepEqual(one("move it to 9pm", history).args, { sessionId: "s3", time: "21:00", flexible: false });
  assert.equal(one("shift that to the evening", history).args.sessionId, "s3");
  assert.equal(one("I finished it", history).args.taskId, "t3");
  assert.equal(one("postpone it", history).args.taskId, "t3");
  assert.equal(one("skip it", history).args.sessionId, "s3");
});

test("'it' with nothing before it is not guessed", () => {
  const r = run("move it to 9pm", []);
  assert.equal(r.actions.length, 0);
});

// ---------------------------------------------------------------------- asking where to
test("'move reading' with nowhere to move it asks, and offers free slots", () => {
  const r = run("move reading");
  assert.equal(r.actions.length, 0);
  assert.match(r.reply, /Where should I move “Reading”/);
  const texts = r.clarify.options.map((o) => o.text);
  assert.ok(texts.includes("move Reading to 18:00"));
  assert.ok(texts.includes("move Reading to tomorrow"));
});

test("each option offered can be tapped and works", () => {
  for (const option of run("move reading").clarify.options) {
    const r = run(option.text);
    assert.equal(r.actions.length, 1, option.text);
  }
});

// ------------------------------------------------------------------------------- not hijacked
test("other uses of the same words are left alone", () => {
  assert.equal(one("set reading to high priority").type, "update_task");
  assert.equal(one("change maths homework due date to monday").args.due, "2026-10-26");
  assert.equal(one("make maths homework 90 minutes").args.minutes, 90);
  assert.equal(one("add task move boxes to the new room").type, "add_task");
  assert.equal(run("do I have any free time tomorrow").actions[0].type, "show_free_time");
});

test("a move to a time that isn't a time is not invented", () => {
  const r = run("move physics to the moon");
  assert.equal(r.actions.length, 0);
});

test("everything produced passes the strict check", () => {
  const phrases = [
    "move physics to 6pm", "swap maths homework and reading", "put reading after dinner", "move maths to tomorrow",
    "postpone reading", "start maths homework today", "lighten my day", "how am I doing", "what should I focus on",
    "I feel overwhelmed", "move the last session to 7pm", "shift reading before physics",
  ];
  for (const p of phrases) for (const a of run(p).actions) assert.ok(validateAction(a).action, `${p} -> ${JSON.stringify(a)} -> ${validateAction(a).error}`);
});

// -------------------------------------------------------------------------- how people really type
test("filler words at the start don't matter", () => {
  assert.equal(one("ok. can you shift reading to after dinner").args.time, "20:40");
  assert.equal(one("actually, move physics to 6pm").args.sessionId, "s2");
  assert.equal(one("hey, add task call home 10 min").type, "add_task");
  assert.equal(one("so I finished maths homework").type, "complete_task");
  assert.equal(one("hmm swap maths homework and reading").type, "swap_sessions");
  assert.equal(one("also move reading to tonight").args.time, "20:00");
});

test("a lone 'ok' or 'hey' is still a message", () => {
  assert.match(run("ok").reply, /welcome|Anytime|help|Happy/i);
  assert.match(run("hey").reply, /Good (morning|afternoon|evening)/);
});

test("a conversation: the move fails to parse a name, then 'it' picks up what was just discussed", () => {
  const history = [
    { role: "user", text: "ok. can you shift reading to after dinner" },
    { role: "assistant", text: "Putting “Reading” after Dinner (from 20:40)." },
  ];
  const a = one("actually swap it with maths homework", history);
  assert.deepEqual(a.args, { sessionA: "s3", sessionB: "s1" });
});

test("the assistant's own examples are not what 'it' refers to", () => {
  const history = [
    { role: "user", text: "blah" },
    { role: "assistant", text: "Hmm, I didn't quite get that one. Try something like:\n• add task maths homework 45 min due friday\n• I finished maths homework" },
  ];
  assert.equal(run("move it to 9pm", history).actions.length, 0);
});

test("'to after dinner' and 'to before physics' work like 'after dinner' and 'before physics'", () => {
  assert.equal(one("shift reading to after dinner").args.time, "20:40");
  assert.equal(one("move reading to before physics").args.time, "16:25");
});

