// A free assistant: understands common commands without any AI service.
//   interpret("add task maths homework 45 min due friday", context) -> { reply, actions, clarify }
// It finds the task you mean by its words (so "mark maths homework done" works however you phrase it),
// then pulls out dates, times, durations and priorities. Anything it can't follow gets a helpful answer
// instead of a guess. With an AI key on the server, the AI is used instead for freer wording.
const { parseDate, parseTime, parseDuration, label, hhmm, WEEKDAYS } = require("./dates");

// ------------------------------------------------------------------------------ small helpers
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
  "habit", "with", "worked", "spent", "studied", "just", "already", "have", "is", "are", "was", "now",
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

const EXAMPLES = [
  "add task maths homework 45 min due friday",
  "what's my plan today?",
  "how much free time do I have tomorrow?",
  "I finished maths homework",
  "move physics to 6pm",
  "change maths homework due date to monday",
  "add habit coding practice every day 45 min",
  "re-plan my day",
];

// ---------------------------------------------------------------------------- slot extraction
/** Pulls priority, length, due date out of a sentence; what is left is the title. */
function extractSlots(raw, today, { dates = true } = {}) {
  let s = raw;
  const slots = {};

  let m = /\b(high|low|medium)[\s-]*priority\b/i.exec(s) || /\bpriority[\s:]*(?:is|of|to)?[\s:]*(high|low|medium)\b/i.exec(s);
  if (m) {
    slots.priority = capital(m[1].toLowerCase());
    s = remove(s, m[0]);
  } else if ((m = /\b(urgent|asap|important|critical)\b/i.exec(s))) {
    slots.priority = "High";
    s = remove(s, m[0]);
  }

  const dur = parseDuration(s);
  if (dur) {
    slots.minutes = dur.minutes;
    s = remove(s, dur.text, ["for", "takes", "take", "about", "around", "of", "that takes"]);
  }

  const date = dates ? parseDate(s, today) : null;
  if (date) {
    slots.due = date.date;
    s = remove(s, date.text, ["due on", "due by", "due", "by", "before", "on", "until", "till", "for", "from"]);
  }
  return { slots, rest: s };
}

function cleanTitle(s) {
  let t = s.replace(/\s+/g, " ").trim();
  t = t.replace(/^(?:(?:a|an|the|new|task|todo|to-do|to do|reminder|assignment|called|named|that|to|for|please)\b[\s:,-]*)+/i, "");
  t = t.replace(/(?:[\s,;-]+(?:for|by|due|on|at|in|with|and|that takes|takes))+$/i, "");
  t = t.replace(/^[\s:,;.-]+|[\s:,;.-]+$/g, "").trim();
  return capital(t).slice(0, 200);
}

// ------------------------------------------------------------------------------------ context
function normalize(context) {
  const c = context || {};
  const day = (d) => ({ sessions: Array.isArray(d && d.sessions) ? d.sessions : [], freeMinutes: Number.isFinite(d && d.freeMinutes) ? d.freeMinutes : null });
  return {
    today: /^\d{4}-\d{2}-\d{2}$/.test(c.today) ? c.today : new Date().toISOString().slice(0, 10),
    tasks: (Array.isArray(c.tasks) ? c.tasks : []).filter((t) => t && t.id && t.title),
    habits: (Array.isArray(c.habits) ? c.habits : []).filter((h) => h && h.id && h.title),
    plan: day(c.plan),
    tomorrow: day(c.tomorrow),
  };
}

const openTasks = (ctx) => ctx.tasks.filter((t) => !t.completed);
const plannedSessions = (ctx) => ctx.plan.sessions.filter((s) => s.status === "PLANNED");

// ------------------------------------------------------------------------------------ intents
function helpIntent(raw, l) {
  if (!/^(help|what can you do|what can i ask|commands|examples?|how do (i|you)|how does this work)\b/.test(l)) return null;
  return say("I can add and change tasks and habits, mark things done, move a session to another time, and tell you your plan and free time. For example:\n• " + EXAMPLES.join("\n• "));
}

function smallTalkIntent(raw, l) {
  if (/^(hi+|hello|hey+|good (morning|afternoon|evening))[\s!.]*$/.test(l)) return say("Hi! What would you like to do? Try “what's my plan today?” or say “help”.");
  if (/^(thanks?|thank you|ok(ay)?|cool|great|nice|got it|perfect)[\s!.]*$/.test(l)) return say("You're welcome!");
  return null;
}

function replanIntent(raw, l) {
  if (!/\b(re-?plan|re-?shuffle|re-?do my (plan|day|schedule)|re-?organi[sz]e|re-?arrange|fix my (day|plan|schedule)|re-?schedule (my|the) (day|plan|schedule))\b/.test(l)) return null;
  return say("Re-planning the rest of today. Anything you moved by hand stays where it is.", [{ type: "replan", args: {} }]);
}

function whichDay(l, ctx) {
  if (/\btomorrow\b/.test(l)) return "tomorrow";
  const date = parseDate(l, ctx.today);
  if (date && date.date !== ctx.today) return null; // another day: not supported
  return "today";
}

function queryIntent(raw, l, ctx) {
  if (/\b(free (time|hours?|slots?)|spare time|how (much|many)\b.*\b(free|time|hours)|am i free|when am i free)\b/.test(l)) {
    const day = whichDay(l, ctx);
    if (!day) return say("I can show free time for today and tomorrow.");
    return say(`Checking your free time ${day}.`, [{ type: "show_free_time", args: { day } }]);
  }
  if (/\b(overdue|past due|late tasks?|behind on)\b/.test(l)) return say("Here are your overdue tasks.", [{ type: "show_tasks", args: { filter: "overdue" } }]);
  if (/\b(what|which|show|list)\b.*\b(due|deadlines?)\b/.test(l)) return say("Here's what is due soon.", [{ type: "show_tasks", args: { filter: "due_soon" } }]);
  if (/\b(what|which|did i)\b.*\bmiss(ed)?\b|\bmissed\b.*\btoday\b/.test(l)) return say("Here's what you missed today.", [{ type: "show_missed", args: {} }]);
  if (
    /\b(what('?s| is| do i have| have i got)|show|tell me|give me|see|check|open)\b.*\b(plan|schedule|agenda|timeline|day|timetable)\b/.test(l) ||
    /^(my )?(plan|schedule|agenda|day)( for)?( today| tomorrow)?$/.test(l) ||
    /\bwhat do i have\b/.test(l)
  ) {
    const day = whichDay(l, ctx);
    if (!day) return say("I can show the plan for today and tomorrow.");
    return say(`Here's your plan for ${day}.`, [{ type: "show_plan", args: { day } }]);
  }
  if (/\b(show|list|what are|what('?s| is)|see)\b.*\b(tasks?|to-?dos?)\b/.test(l)) return say("Here are your open tasks.", [{ type: "show_tasks", args: { filter: "open" } }]);
  return null;
}

function addHabitIntent(raw, l, ctx) {
  if (!/\b(add|create|new|start|make)\b.*\bhabit\b|\bhabit\b.*\b(add|create|new)\b/.test(l)) return null;
  let { slots, rest } = extractSlots(raw, ctx.today, { dates: false });
  let kind = null;
  let days;
  let perWeek;

  let m;
  const weekdayList = /\bon ((?:(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*)(?:[ ,]+(?:and\s+)?(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*)*)\b/i.exec(rest);
  if ((m = /\b(once|twice|one|two|three|four|five|six|\d)\s*(?:times?|x)?\s*(?:a|per|each|\/)\s*week\b/i.exec(rest))) {
    const words = { once: 1, twice: 2, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6 };
    perWeek = words[m[1].toLowerCase()] || Number(m[1]);
    kind = "WEEKLY";
    rest = remove(rest, m[0]);
  } else if (weekdayList) {
    days = [...new Set(weekdayList[1].toLowerCase().split(/[^a-z]+/).filter(Boolean).map((w) => WEEKDAYS[w]).filter(Boolean))].sort();
    kind = "DAYS";
    rest = remove(rest, weekdayList[0]);
  } else if ((m = /\b(weekdays|week days)\b/i.exec(rest))) {
    days = [1, 2, 3, 4, 5];
    kind = "DAYS";
    rest = remove(rest, m[0]);
  } else if ((m = /\bweekends?\b/i.exec(rest))) {
    days = [6, 7];
    kind = "DAYS";
    rest = remove(rest, m[0]);
  } else if ((m = /\b(every ?day|daily|each day)\b/i.exec(rest))) {
    kind = "DAILY";
    rest = remove(rest, m[0]);
  }

  rest = rest.replace(/\b(add|create|new|start|make)\b/gi, " ").replace(/\ba habit\b|\bhabit\b/gi, " ");
  const title = cleanTitle(rest);
  if (!title) return say("What should the habit be called? For example: “add habit coding practice every day 45 min”.");
  if (!kind) {
    return say(`How often should “${title}” happen?`, [], {
      question: `How often should “${title}” happen?`,
      options: [
        { label: "Every day", text: `add habit ${title} every day${slots.minutes ? ` ${slots.minutes} min` : ""}` },
        { label: "2 times a week", text: `add habit ${title} 2 times a week${slots.minutes ? ` ${slots.minutes} min` : ""}` },
        { label: "Mon, Wed, Fri", text: `add habit ${title} on mon wed fri${slots.minutes ? ` ${slots.minutes} min` : ""}` },
      ],
    });
  }
  const args = { title, kind, ...(slots.minutes ? { minutes: Math.max(5, slots.minutes) } : {}), ...(slots.priority ? { priority: slots.priority } : {}) };
  if (kind === "DAYS") args.days = days;
  if (kind === "WEEKLY") args.perWeek = perWeek;
  const how = kind === "DAILY" ? "every day" : kind === "WEEKLY" ? `${perWeek} time${perWeek === 1 ? "" : "s"} a week` : "on fixed days";
  return say(`Adding the habit “${title}” (${how}${args.minutes ? `, ${fmtMinutes(args.minutes)}` : ""}).`, [{ type: "add_habit", args }]);
}

function addTaskIntent(raw, l, ctx) {
  let body = null;
  let m;
  if ((m = /^(?:please\s+)?(?:can you\s+|could you\s+)?(?:add|create|make|new|put|set up)\s+(?:a\s+|an\s+|the\s+)?(?:new\s+)?(?:task|todo|to-do|to do|reminder|assignment)\b[\s:,-]*(.*)$/i.exec(raw))) body = m[1];
  else if ((m = /^(?:please\s+)?(?:add|create|new)\s+(.+)$/i.exec(raw))) body = m[1];
  else if ((m = /^(?:remind me to|i (?:have|need|must|should|got) to|i've got to|ive got to|don't forget to|dont forget to)\s+(.+)$/i.exec(raw))) body = m[1];
  else if ((m = /^(?:todo|task)[:\s]+(.+)$/i.exec(raw))) body = m[1];
  if (body === null) return null;

  const { slots, rest } = extractSlots(body, ctx.today);
  const title = cleanTitle(rest);
  if (!title) return say("What should the task be called? For example: “add task maths homework 45 min due friday”.");

  const args = { title, ...(slots.minutes ? { minutes: slots.minutes } : {}), ...(slots.priority ? { priority: slots.priority } : {}), ...(slots.due ? { due: slots.due } : {}) };
  const details = [slots.minutes ? fmtMinutes(slots.minutes) : null, slots.due ? `due ${label(slots.due)}` : null, slots.priority ? `${slots.priority.toLowerCase()} priority` : null].filter(Boolean);
  return say(`Adding the task “${title}”${details.length ? ` (${details.join(", ")})` : ""}.`, [{ type: "add_task", args }]);
}

function deleteIntent(raw, l, ctx) {
  const m = /^(?:please\s+)?(?:delete|remove|cancel|drop|get rid of|scrap)\b(.*)$/.exec(l);
  if (!m) return null;
  // "remove the due date from ..." changes a task, it doesn't delete it
  if (/\b(due date|due|deadline|priority|duration|length)\b/.test(l)) return null;
  const sentence = m[1];
  const task = findBest(sentence, openTasks(ctx));
  const habit = findBest(sentence, ctx.habits);
  if (task && task.ambiguous) return ask(raw, "Which task do you mean?", task.ambiguous);
  if (task && task.item && (!habit || !habit.item)) return say(`Delete “${task.item.title}”? I'll ask you to confirm.`, [{ type: "delete_task", args: { taskId: task.item.id } }]);
  if (habit && habit.item && !task) return say(`Delete the habit “${habit.item.title}”? I'll ask you to confirm.`, [{ type: "delete_habit", args: { habitId: habit.item.id } }]);
  if (habit && habit.ambiguous) return ask(raw, "Which habit do you mean?", habit.ambiguous);
  return say("I couldn't find a task or habit with that name. Say “show my tasks” to see them.");
}

function progressIntent(raw, l, ctx) {
  if (!/\b(worked on|spent|studied|practi[sc]ed|did)\b/.test(l)) return null;
  const dur = parseDuration(l);
  if (!dur) return null;
  const task = findBest(l, openTasks(ctx));
  if (!task || !task.item) return null;
  return say(`Noted: ${fmtMinutes(dur.minutes)} on “${task.item.title}”.`, [{ type: "log_progress", args: { taskId: task.item.id, minutes: dur.minutes } }]);
}

function completeIntent(raw, l, ctx) {
  const done =
    /\b(?:i\s+)?(?:have\s+)?(?:just\s+)?(?:done|finished|completed|did)\b/.test(l) ||
    /\bmark\b.*\b(done|complete|completed|finished)\b/.test(l) ||
    /\b(?:is|are)\s+(?:done|finished|complete|completed)\b/.test(l) ||
    /\b(?:i'?m|am)\s+(?:done|finished)\s+with\b/.test(l) ||
    /^(?:done|finished|completed?)\b/.test(l);
  if (!done) return null;
  const task = findBest(l, openTasks(ctx));
  const habit = findBest(l, ctx.habits);
  if (task && task.ambiguous) return ask(raw, "Which task do you mean?", task.ambiguous);
  const taskScore = task && task.item ? 1 : 0;
  const habitScore = habit && habit.item ? 1 : 0;
  if (taskScore) return say(`Marking “${task.item.title}” as done.`, [{ type: "complete_task", args: { taskId: task.item.id } }]);
  if (habitScore) return say(`Marking “${habit.item.title}” as done for today.`, [{ type: "complete_habit", args: { habitId: habit.item.id } }]);
  return say("I couldn't tell which task you finished. Say “show my tasks” to see them.");
}

function skipIntent(raw, l, ctx) {
  if (!/^(?:please\s+)?(?:skip|postpone|push back|snooze)\b|\blater\b/.test(l)) return null;
  const sessions = plannedSessions(ctx);
  const session = findBest(l, sessions);
  if (session && session.ambiguous) return ask(raw, "Which session do you mean?", session.ambiguous);
  if (session && session.item) return say(`Skipping “${session.item.title}”: I'll plan it again for later today.`, [{ type: "skip_session", args: { sessionId: session.item.id } }]);
  return say("I couldn't find that among today's planned sessions. Say “what's my plan today?” to see them.");
}

/** move / reschedule / change / rename / make: figures out which kind of change from the words used. */
function modifyIntent(raw, l, ctx) {
  // "remove the due date from X", "clear the deadline of X"
  if (/^(?:please\s+)?(?:remove|clear|delete|drop)\b.*\b(due date|due|deadline)\b/.test(l)) {
    const task = findBest(l, openTasks(ctx));
    if (task && task.ambiguous) return ask(raw, "Which task do you mean?", task.ambiguous);
    if (task && task.item) return say(`Removing the due date from “${task.item.title}”.`, [{ type: "update_task", args: { taskId: task.item.id, due: "none" } }]);
    return say("I couldn't find that task. Say “show my tasks” to see them.");
  }

  const verb = /^(?:please\s+)?(?:can you\s+)?(move|shift|reschedule|schedule|put|set|change|update|rename|make|edit|extend|shorten|do)\b/.exec(l);
  if (!verb) return null;

  const time = parseTime(l);
  const duration = parseDuration(l);
  const date = parseDate(l, ctx.today);
  const tasks = openTasks(ctx);

  // rename "X to Y"
  if (/\brename\b|\bchange\b.*\b(name|title)\b/.test(l)) {
    const task = findBest(l, tasks);
    const m = /\bto\s+(.+)$/i.exec(raw);
    if (task && task.item && m) {
      const title = cleanTitle(m[1]);
      return say(`Renaming “${task.item.title}” to “${title}”.`, [{ type: "update_task", args: { taskId: task.item.id, title } }]);
    }
    return say("Which task, and what should it be called? For example: “rename maths homework to algebra homework”.");
  }

  // priority
  const pr = /\b(high|low|medium)\b/.exec(l);
  if (/\bpriority\b/.test(l) && pr) {
    const task = findBest(l, tasks);
    if (task && task.ambiguous) return ask(raw, "Which task do you mean?", task.ambiguous);
    if (task && task.item) return say(`Setting “${task.item.title}” to ${pr[1]} priority.`, [{ type: "update_task", args: { taskId: task.item.id, priority: capital(pr[1]) } }]);
  }

  // a time of day: move a planned session today
  if (time && !/\b(due|deadline)\b/.test(l)) {
    if (date && date.date !== ctx.today) return say("I can only move today's sessions. To change the day, change the task's due date instead: “change maths homework due date to friday”.");
    const session = findBest(l, plannedSessions(ctx));
    if (session && session.ambiguous) return ask(raw, "Which session do you mean?", session.ambiguous);
    if (session && session.item) {
      const where = time.flexible ? `around ${hhmm(time.minutes)}` : `to ${hhmm(time.minutes)}`;
      return say(`Moving “${session.item.title}” ${where}.`, [{ type: "move_session", args: { sessionId: session.item.id, time: hhmm(time.minutes), flexible: time.flexible } }]);
    }
    const task = findBest(l, tasks);
    if (task && task.item) return say(`“${task.item.title}” has no session planned for today. I can only move today's sessions.`);
    return say("I couldn't find that among today's planned sessions. Say “what's my plan today?” to see them.");
  }

  // a new length
  if (duration && /\b(make|change|set|extend|shorten|update|edit)\b/.test(l) && !/\b(due|deadline)\b/.test(l)) {
    const task = findBest(l, tasks);
    if (task && task.ambiguous) return ask(raw, "Which task do you mean?", task.ambiguous);
    if (task && task.item) return say(`Changing “${task.item.title}” to ${fmtMinutes(duration.minutes)}.`, [{ type: "update_task", args: { taskId: task.item.id, minutes: duration.minutes } }]);
  }

  // a new due date (or removing it)
  if (date || /\b(due|deadline)\b/.test(l)) {
    const task = findBest(l, tasks);
    if (task && task.ambiguous) return ask(raw, "Which task do you mean?", task.ambiguous);
    if (task && task.item) {
      if (/\b(no|remove|clear|without)\b.*\b(due|deadline|date)\b/.test(l)) return say(`Removing the due date from “${task.item.title}”.`, [{ type: "update_task", args: { taskId: task.item.id, due: "none" } }]);
      if (date) return say(`Setting “${task.item.title}” due ${label(date.date)}.`, [{ type: "update_task", args: { taskId: task.item.id, due: date.date } }]);
      return say("When should it be due? For example: “change maths homework due date to friday”.");
    }
    return say("I couldn't find that task. Say “show my tasks” to see them.");
  }
  return null;
}

const INTENTS = [helpIntent, smallTalkIntent, replanIntent, queryIntent, addHabitIntent, addTaskIntent, deleteIntent, progressIntent, completeIntent, skipIntent, modifyIntent];

function interpret(text, context) {
  const raw = String(text || "").trim().slice(0, 500);
  const l = raw.toLowerCase().replace(/[!?.]+$/g, "").replace(/\s+/g, " ").trim();
  if (!l) return say("Type a message, for example “add task maths homework 45 min due friday”.");
  const ctx = normalize(context);
  for (const intent of INTENTS) {
    const result = intent(raw, l, ctx);
    if (result) return result;
  }
  return say("I'm not sure what you mean. Here are some things I understand:\n• " + EXAMPLES.slice(0, 6).join("\n• "));
}

module.exports = { interpret, extractSlots, cleanTitle, findBest };
