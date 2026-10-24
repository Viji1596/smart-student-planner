// Small helpers shared by the free assistant's modules.
const { label } = require("./dates");

const TITLE_STOP = new Set(["the", "a", "an", "of", "to", "for", "and", "my"]);
const stem = (w) => (w.length > 3 && w.endsWith("s") ? w.slice(0, -1) : w);
const tokens = (s, stop = TITLE_STOP) =>
  String(s).toLowerCase().split(/[^a-z0-9]+/).filter((w) => w.length >= 2 && !stop.has(w)).map(stem);

function fmtMinutes(n) {
  if (n < 60) return `${n} min`;
  const h = Math.floor(n / 60);
  const m = n % 60;
  return m ? `${h} h ${m} min` : `${h} h`;
}

const capital = (s) => (s ? s[0].toUpperCase() + s.slice(1) : s);

/** Removes [matched] from [text] (and a preposition right before it, such as "due" or "for"). */
function remove(text, matched, prepositions = []) {
  const i = text.toLowerCase().indexOf(String(matched).toLowerCase());
  if (i < 0) return text;
  let start = i;
  const before = text.slice(0, i);
  for (const p of prepositions) {
    const m = new RegExp(`\\b${p}\\s*$`, "i").exec(before);
    if (m) {
      start = m.index;
      break;
    }
  }
  return `${text.slice(0, start)} ${text.slice(i + matched.length)}`;
}

// Words that are about the request (the command, a date, a time), not about which task it is.
const NOT_ABOUT_THE_TASK = new Set([
  "move", "shift", "reschedule", "schedule", "put", "set", "change", "update", "rename", "make", "edit", "extend", "shorten",
  "do", "did", "done", "finish", "finished", "complete", "completed", "mark", "as", "delete", "remove", "cancel", "skip",
  "postpone", "later", "my", "the", "to", "at", "on", "in", "by", "for", "and", "it", "this", "that", "i", "me", "please",
  "can", "you", "task", "session", "today", "tomorrow", "due", "date", "deadline", "priority", "high", "low", "medium",
  "minute", "min", "hour", "hr", "am", "pm", "morning", "afternoon", "evening", "night", "noon", "next", "week", "weekend",
  "habit", "with", "worked", "spent", "studied", "just", "already", "have", "is", "are", "was", "now", "slot", "one",
  "swap", "switch", "push", "delay", "defer", "start", "bring", "back", "after", "before", "tonight", "first", "second",
  "third", "last", "work", "thing", "about", "from", "up", "off", "out", "not", "no", "so", "then",
  "what", "when", "where", "which", "why", "how", "who", "time", "much", "many", "long", "begin", "planned", "scheduled",
  "does", "will", "would", "should", "could", "get", "got", "any", "there", "here", "going", "doing", "plan", "am",
  "monday", "mon", "tuesday", "tue", "wednesday", "wed", "thursday", "thu", "friday", "fri", "saturday", "sat", "sunday", "sun",
]);

/**
 * The task (or habit, or session) the sentence is about.
 * First: the item whose title words (most of them) appear in the sentence.
 * Otherwise: the item whose title contains every word you typed that is about the task ("physics" for
 * "Physics lab report"). If that points to several items, they are returned as ambiguous.
 */
function findBest(sentence, items, titleOf = (x) => x.title) {
  const sent = new Set(tokens(sentence, new Set()));
  const scored = items
    .map((item) => {
      const t = [...new Set(tokens(titleOf(item)))];
      if (!t.length) return null;
      return { item, score: t.filter((w) => sent.has(w)).length / t.length, size: t.length };
    })
    .filter((x) => x && x.score >= 0.6)
    .sort((a, b) => b.score - a.score || b.size - a.size);
  if (scored.length) {
    const top = scored[0];
    const tied = scored.filter((s) => s.score === top.score && s.size === top.size);
    return tied.length > 1 ? { ambiguous: tied.slice(0, 3).map((s) => s.item) } : { item: top.item };
  }

  // second chance: a partial name
  const typed = [...sent].filter((w) => !NOT_ABOUT_THE_TASK.has(w) && !/^\d+$/.test(w) && !/^\d+(am|pm)$/.test(w));
  if (!typed.length) return null;
  const partial = items.filter((item) => {
    const t = new Set(tokens(titleOf(item)));
    return typed.every((w) => t.has(w));
  });
  if (partial.length === 1) return { item: partial[0] };
  if (partial.length > 1) return { ambiguous: partial.slice(0, 3) };
  return null;
}

function say(reply, actions = [], clarify = null) {
  return { reply, actions, clarify };
}

function ask(raw, question, items) {
  return say(question, [], { question, options: items.map((i) => ({ label: i.title, text: `${raw} ${i.title}` })) });
}

/** The same input always gives the same pick, so the assistant varies its words without being random. */
function hash(text) {
  let h = 7;
  for (const ch of String(text)) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return h;
}
const pick = (list, seed) => list[hash(seed) % list.length];

function normalize(context) {
  const c = context || {};
  const list = (x) => (Array.isArray(x) ? x : []);
  const day = (d) => ({
    sessions: list(d && d.sessions),
    freeMinutes: Number.isFinite(d && d.freeMinutes) ? d.freeMinutes : null,
    busy: list(d && d.busy),
    freeBlocks: list(d && d.freeBlocks),
  });
  return {
    today: /^\d{4}-\d{2}-\d{2}$/.test(c.today) ? c.today : new Date().toISOString().slice(0, 10),
    nowMin: Number.isFinite(c.nowMin) ? c.nowMin : null,
    tasks: list(c.tasks).filter((t) => t && t.id && t.title),
    habits: list(c.habits).filter((h) => h && h.id && h.title),
    plan: day(c.plan),
    tomorrow: day(c.tomorrow),
    report: c.report || null,
    focus: c.focus && c.focus.taskId && c.focus.title ? { taskId: c.focus.taskId, title: c.focus.title } : null,
  };
}

const openTasks = (ctx) => ctx.tasks.filter((t) => !t.completed);
const plannedSessions = (ctx) => ctx.plan.sessions.filter((s) => s.status === "PLANNED");
const toMinutes = (hm) => {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(hm || ""));
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
};
const sessionMinutes = (s) => (toMinutes(s.end) ?? 0) - (toMinutes(s.start) ?? 0);

module.exports = {
  tokens, fmtMinutes, capital, remove, findBest, say, ask, hash, pick, normalize, openTasks, plannedSessions,
  toMinutes, sessionMinutes, label, NOT_ABOUT_THE_TASK,
};
