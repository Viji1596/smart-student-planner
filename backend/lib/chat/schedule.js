// Moving things around, in plain English: "move physics to 6", "swap maths and reading", "put reading
// after dinner", "shift it to the evening", "move maths to tomorrow", "start maths today".
const { parseDate, parseTime, label, hhmm, addDays } = require("./dates");
const { say, ask, findBest, openTasks, plannedSessions, toMinutes, sessionMinutes, fmtMinutes, sessionGroups,
} = require("./common");

const STRONG_VERBS = new Set(["move", "shift", "reschedule", "swap", "switch", "postpone", "push", "delay", "slide", "pull"]);
const VERBS = "move|shift|reschedule|schedule|put|set|do|push|bring|slide|postpone|delay|pull";
const POLITE = "(?:please\\s+|can you\\s+|could you\\s+|would you\\s+|i want to\\s+|i'd like to\\s+|i would like to\\s+|let'?s\\s+|just\\s+)*";
const PREPS = "in the slot of|into the slot of|in the place of|in place of|instead of|to|at|for|after|before|around|until|till|by|on|in|during";
const SPLIT = new RegExp(`^${POLITE}(${VERBS})\\s+(.+?)\\s+(${PREPS})\\s+(.+)$`);
const NOW_AT_END = new RegExp(`^${POLITE}(move|shift|schedule|put|do|start|begin|bring|work on)\\s+(.+?)\\s+(now|right now|asap|immediately|straight away)$`);
const SWAP = new RegExp(`^${POLITE}(?:swap|switch|exchange)\\s+(.+?)\\s+(?:and|with|&)\\s+(.+)$`);
const MOVE_ALONE = new RegExp(`^${POLITE}(move|shift|reschedule)\\s+(.+)$`);
const PRONOUN = /^(it|this|that|them|those|these|that one|this one|the same|the same one|same one|the one|that session|this session)$/;

const byStart = (a, b) => (toMinutes(a.start) ?? 0) - (toMinutes(b.start) ?? 0);

// the assistant's own lists of examples mention made-up tasks: they are not "what we were talking about"
const EXAMPLE_REPLY = /things I understand|Try something like|planner assistant\. I can/;

/** The task the app says the student was just working with (it remembers what the last action touched). */
function focusTarget(ctx) {
  if (!ctx.focus) return null;
  const task = ctx.tasks.find((t) => t.id === ctx.focus.taskId && !t.completed);
  const session = plannedSessions(ctx).find((x) => x.refId === ctx.focus.taskId);
  if (session) return { session };
  if (task) return { task };
  return null;
}

/** The session or task talked about most recently in the conversation. */
function lastMentioned(history, ctx) {
  const focused = focusTarget(ctx);
  if (focused) return focused;
  const sessions = plannedSessions(ctx);
  const tasks = openTasks(ctx);
  for (let i = history.length - 1; i >= 0; i--) {
    const text = String((history[i] && history[i].text) || "");
    if (history[i] && history[i].role === "assistant" && EXAMPLE_REPLY.test(text)) continue;
    const s = findBest(text, sessions);
    if (s && s.item) return { session: s.item };
    const t = findBest(text, tasks);
    if (t && t.item) {
      const session = sessions.find((x) => x.refId === t.item.id);
      return session ? { session } : { task: t.item };
    }
  }
  return null;
}

/** Which session (or task) a piece of the sentence points to: a name, "it", "my first session", "the 4pm one". */
function pickSubject(text, ctx, history) {
  const sessions = plannedSessions(ctx).slice().sort(byStart);
  const trimmed = String(text).trim();
  const s = trimmed
    .replace(/^(?:the|my|this|that|our|your)\s+/, "")
    .replace(/\s+(?:sessions?|slots?|tasks?|work|ones?|things?)$/, "")
    .trim();

  if (!s || PRONOUN.test(trimmed) || PRONOUN.test(s)) return lastMentioned(history, ctx);

  const ordinal = /^(first|1st|second|2nd|third|3rd|fourth|4th|next|upcoming|last|final|earliest|latest)\b/.exec(s);
  if (ordinal && sessions.length) {
    const w = ordinal[1];
    if (/^(last|final|latest)$/.test(w)) return { session: sessions[sessions.length - 1], slot: true };
    if (/^(next|upcoming)$/.test(w)) {
      const next = sessions.find((x) => (toMinutes(x.start) ?? 0) > (ctx.nowMin ?? 0)) || sessions[0];
      return { session: next, slot: true };
    }
    const idx = { first: 0, "1st": 0, earliest: 0, second: 1, "2nd": 1, third: 2, "3rd": 2, fourth: 3, "4th": 3 }[w];
    return sessions[idx] ? { session: sessions[idx], slot: true } : null;
  }

  const clock = /^(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$/.exec(s);
  if (clock) {
    const h = Number(clock[1]);
    const m = clock[2] ? Number(clock[2]) : 0;
    const wanted = clock[3] ? [((h % 12) + (clock[3] === "pm" ? 12 : 0)) * 60 + m] : [h * 60 + m, ((h % 12) + 12) * 60 + m];
    const match = sessions.filter((x) => wanted.includes(toMinutes(x.start)));
    if (match.length === 1) return { session: match[0], slot: true };
    if (match.length > 1) return { ambiguous: match };
  }

  // by name: a task is one thing, even when the day splits it into several sessions
  const found = findBest(s, sessionGroups(ctx));
  if (found) return found.ambiguous ? { ambiguous: found.ambiguous } : { session: found.item.sessions[0], group: found.item };
  const task = findBest(s, openTasks(ctx));
  if (task && task.item) return { task: task.item };
  if (task && task.ambiguous) return { ambiguous: task.ambiguous };
  return null;
}

/** "after dinner", "before physics": a time on the day that something else ends or starts. */
function anchorFor(text, ctx, excludeId) {
  const words = String(text).toLowerCase();
  const sessions = plannedSessions(ctx).filter((s) => s.id !== excludeId);
  const s = findBest(words, sessions);
  if (s && s.item) return { start: toMinutes(s.item.start), end: toMinutes(s.item.end), name: s.item.title };

  const busy = ctx.plan.busy.filter((b) => b && b.label);
  const classes = busy.filter((b) => String(b.kind || "").toUpperCase() === "CLASS");
  if (/\b(class|classes|college|lectures?|lab)\b/.test(words) && classes.length) {
    const sorted = classes.slice().sort((a, b) => (toMinutes(a.start) ?? 0) - (toMinutes(b.start) ?? 0));
    return { start: toMinutes(sorted[0].start), end: toMinutes(sorted[sorted.length - 1].end), name: "class", firstEnd: toMinutes(sorted[0].end), lastStart: toMinutes(sorted[sorted.length - 1].start) };
  }
  for (const b of busy) {
    const lab = String(b.label).toLowerCase();
    if (lab.length >= 3 && words.includes(lab)) return { start: toMinutes(b.start), end: toMinutes(b.end), name: b.label };
    const firstWord = lab.split(/\s+/)[0];
    if (firstWord.length >= 4 && words.split(/\W+/).includes(firstWord)) return { start: toMinutes(b.start), end: toMinutes(b.end), name: b.label };
  }
  return null;
}

const roundUp5 = (m) => Math.ceil(m / 5) * 5;

/** Minutes of a task still to do (30 when the app didn't say). */
function remainingMinutes(task, ctx) {
  const full = ctx.tasks.find((t) => t.id === task.id) || task;
  return Math.max(0, (full.minutes || 30) - (full.spent || 0)) || 30;
}

/** A task with no session today, given a time today: a session is created there (and kept). */
function placeTask(task, how, ctx) {
  const duration = Math.min(remainingMinutes(task, ctx), 90);
  let at;
  let flexible = false;
  if (how.now) {
    if (ctx.nowMin === null) return askWhere(task.title, duration, ctx);
    at = roundUp5(ctx.nowMin);
    flexible = true;
  } else if (how.relative) {
    const anchor = anchorFor(how.target, ctx, null);
    if (!anchor || anchor.start === null) {
      return say(`I couldn't tell what “${how.target}” refers to. Try a time (“to 6pm”), or the name of a session or a meal.`);
    }
    if (how.prep === "after") {
      at = roundUp5(anchor.end);
      flexible = true;
    } else {
      at = anchor.start - duration - 5;
      if (at < 0) return say(`There's no room before ${anchor.name} today.`);
    }
  } else {
    at = how.time.minutes;
    flexible = how.time.flexible;
  }
  return say(
    `“${task.title}” isn't planned for today yet, so I'll schedule it ${flexible ? "around" : "at"} ${hhmm(at)}.`,
    [{ type: "place_task", args: { taskId: task.id, time: hhmm(at), flexible } }]
  );
}

/** Chips with a few of today's free slots that fit, plus "tomorrow", to answer "where to?". */
function whereOptions(title, durationMin, ctx) {
  const options = [];
  for (const b of ctx.plan.freeBlocks) {
    const a = toMinutes(b.start);
    const z = toMinutes(b.end);
    const from = Math.max(a ?? 0, ctx.nowMin === null ? 0 : roundUp5(ctx.nowMin));
    if (a !== null && z !== null && z - from >= durationMin) options.push({ label: `${hhmm(from)}`, text: `move ${title} to ${hhmm(from)}` });
    if (options.length >= 3) break;
  }
  options.push({ label: "Tomorrow", text: `move ${title} to tomorrow` });
  return options;
}

function askWhere(title, durationMin, ctx) {
  const question = `Where should I move “${title}”? You can say a time (“to 6pm”), “after dinner”, “before <another session>”, “swap with <another session>”, or “tomorrow”.`;
  return say(question, [], { question, options: whereOptions(title, durationMin, ctx) });
}

/** The whole task a subject means: all of its sessions today. Null when the student pointed at one slot (the 7pm one, the first one). */
function wholeOf(subject, ctx) {
  if (!subject || !subject.session || subject.slot || subject.session.kind !== "TASK") return null;
  const group = subject.group || sessionGroups(ctx).find((g) => g.id === subject.session.refId);
  if (!group) return null;
  return { taskId: group.id, title: group.title, sessions: group.sessions, due: group.due };
}

const spans = (w) => w.sessions.map((x) => `${x.start}\u2013${x.end}`).join(", ");

function describeTaskSwap(a, b) {
  const later = toMinutes(a.sessions[0].start) <= toMinutes(b.sessions[0].start) ? b : a;
  return `Swapping the whole tasks: “${a.title}” (${spans(a)}) and “${b.title}” (${spans(b)}). “${later.title}” will now come first.`;
}

function describeSwap(a, b) {
  return `Swapping “${a.title}” (${a.start}) and “${b.title}” (${b.start}).`;
}

function deferReply(task, until, ctx) {
  const late = task.due && task.due < until;
  const reply =
    `Okay, “${task.title}” will wait until ${label(until)}.` +
    (late ? ` Heads up: it's due ${label(task.due)}, so that would be late.` : "") +
    (until === addDays(ctx.today, 1) ? " It'll be in tomorrow's plan." : "");
  const actions = [{ type: "defer_task", args: { taskId: task.id, until } }];
  const clarify = late
    ? { question: "Change the due date too?", options: [{ label: `Move due date to ${label(until)}`, text: `change ${task.title} due date to ${until}` }, { label: "Keep the due date", text: "ok" }] }
    : null;
  return say(reply, actions, clarify);
}

/** "move / swap / put ... <where>". Returns null when the sentence isn't about scheduling. */
function scheduleIntent(raw, l, ctx, history) {
  // ---- swap two sessions
  let m = SWAP.exec(l);
  if (m) {
    const a = pickSubject(m[1], ctx, history);
    const b = pickSubject(m[2], ctx, history);
    const unclear = (a && a.ambiguous) || (b && b.ambiguous);
    if (unclear) return ask(raw, "Which one do you mean?", unclear);
    if (a && b && a.session && b.session) {
      // naming two tasks swaps the tasks, with all their time; pointing at slots swaps the slots
      const wa = wholeOf(a, ctx);
      const wb = wholeOf(b, ctx);
      if (wa && wb) {
        if (wa.taskId === wb.taskId) return say("That's the same task twice. Which two should I swap?");
        return say(describeTaskSwap(wa, wb), [{ type: "swap_tasks", args: { taskA: wa.taskId, taskB: wb.taskId } }]);
      }
      if (a.session.id === b.session.id) return say("That's the same session twice. Which two should I swap?");
      return say(describeSwap(a.session, b.session), [{ type: "swap_sessions", args: { sessionA: a.session.id, sessionB: b.session.id } }]);
    }
    return say("I can only swap sessions that are planned for today. Say “what's my plan today?” to see them.");
  }

  m = SPLIT.exec(l);
  if (!m) {
    // "do it now", "start maths now": no little word before "now"
    const nowEnd = NOW_AT_END.exec(l);
    if (nowEnd) m = [l, nowEnd[1], nowEnd[2], "to", nowEnd[3]];
  }
  if (!m) {
    // "move reading" with nowhere to move it to
    const alone = MOVE_ALONE.exec(l);
    if (alone && !/\b(due|deadline|priority|due date)\b/.test(l)) {
      const subject = pickSubject(alone[2], ctx, history);
      if (subject && subject.session) {
        return askWhere(subject.session.title, sessionMinutes(subject.session), ctx);
      }
      if (subject && subject.task) {
        return askWhere(subject.task.title, Math.min(remainingMinutes(subject.task, ctx), 90), ctx);
      }
    }
    return null;
  }

  const [, verb, subjectText, firstPrep, targetText] = m;
  let prep = firstPrep;
  let target = targetText.trim();
  // "shift reading to after dinner": the real preposition is the "after"
  const relativeStart = /^(after|before)\s+(.+)$/.exec(target);
  if (relativeStart) {
    prep = relativeStart[1];
    target = relativeStart[2].trim();
  }
  const strong = STRONG_VERBS.has(verb);
  if (/\b(due|deadline|priority|due date)\b/.test(target) || /\b(due|deadline)\b/.test(subjectText)) return null;

  // is the target something about WHEN? If not, leave the sentence to the other intents (e.g. "set reading to high priority")
  const slotOf = /slot of|place of|instead of/.test(prep);
  const date = parseDate(target, ctx.today);
  // "to 6", "at 7:30": the little word before the number is what tells us it's a time
  const time = parseTime(["to", "at", "for", "around", "until", "till", "by"].includes(prep) ? `${prep} ${target}` : target, ctx.nowMin);
  const now = /^(?:now|right now|asap|immediately|straight away|as soon as possible)\b/.test(target);
  const later = /^(?:later|later today|a bit later)\b/.test(target);
  const earlier = /^(?:earlier|a bit earlier|sooner)\b/.test(target);
  const relative = prep === "after" || prep === "before";
  const anything = slotOf || relative || date || time || now || later || earlier;
  if (!anything) return null;

  const subject = pickSubject(subjectText, ctx, history);
  if (!subject) {
    if (!strong) return null;
    return say("I couldn't find that among today's planned sessions. Say “what's my plan today?” to see them.");
  }
  if (subject.ambiguous) return ask(raw, "Which one do you mean?", subject.ambiguous);

  const session = subject.session;
  const task = subject.task || (session && session.kind === "TASK" ? { id: session.refId, title: session.title, due: (ctx.tasks.find((t) => t.id === session.refId) || {}).due } : null);

  // ---- swap with another session ("in the slot of ...", "instead of ...")
  if (slotOf) {
    const other = pickSubject(target, ctx, history);
    if (session && other && other.ambiguous) return ask(raw, "Which one do you mean?", other.ambiguous);
    if (session && other && other.session && other.session.id !== session.id) {
      const wa = wholeOf(subject, ctx);
      const wb = wholeOf(other, ctx);
      if (wa && wb && wa.taskId !== wb.taskId) {
        return say(describeTaskSwap(wa, wb), [{ type: "swap_tasks", args: { taskA: wa.taskId, taskB: wb.taskId } }]);
      }
      return say(describeSwap(session, other.session), [{ type: "swap_sessions", args: { sessionA: session.id, sessionB: other.session.id } }]);
    }
    return say("Which of today's sessions should it take the place of?");
  }

  // ---- another day: put the task off (it's not a due-date change)
  if (date && date.date !== ctx.today) {
    if (session && session.kind === "HABIT") {
      return say(`Habits repeat on their own days, so I can't move “${session.title}” to another day. Say “skip ${session.title}” to skip it today.`);
    }
    if (!task) return say("I couldn't tell which task you mean.");
    return deferReply(task, date.date, ctx);
  }
  if (date && date.date === ctx.today && !time && !now && !later && !earlier && !relative) {
    if (session) return say(`“${session.title}” is already planned for today, at ${session.start}. Tell me a time if you'd like it moved.`);
    if (task) return say(`Bringing “${task.title}” back into today's plan.`, [{ type: "start_task", args: { taskId: task.id } }]);
  }

  // everything below moves a session within today
  if (!session) {
    // a task that isn't planned today: "do it at 9pm" schedules it there
    if (task && !later && !earlier && (time || now || relative)) return placeTask(task, { time, now, relative, prep, target }, ctx);
    return say(task ? `“${task.title}” isn't planned for today. Tell me a time (“at 6pm”) and I'll schedule it, or say “do it tomorrow”.` : "I couldn't find that among today's planned sessions.");
  }
  // naming a task that is planned as several sessions moves all of it, back to back; one slot is moved only when pointed at
  const whole = wholeOf(subject, ctx);
  const several = whole && whole.sessions.length > 1 ? whole : null;
  const duration = several ? several.sessions.reduce((n, x) => n + sessionMinutes(x), 0) : sessionMinutes(session);
  const moveAction = (at, flexible) =>
    several
      ? { type: "move_task", args: { taskId: several.taskId, time: hhmm(at), flexible } }
      : { type: "move_session", args: { sessionId: session.id, time: hhmm(at), flexible } };
  const what = several ? `“${session.title}” (all ${several.sessions.length} sessions, ${fmtMinutes(duration)})` : `“${session.title}”`;

  if (later) return say(`Okay, I'll plan “${session.title}” again for later today.`, [{ type: "skip_session", args: { sessionId: session.id } }]);
  if (earlier) return askWhere(session.title, duration, ctx);

  if (now) {
    const at = ctx.nowMin === null ? null : roundUp5(ctx.nowMin);
    if (at === null) return askWhere(session.title, duration, ctx);
    return say(`Moving ${what} to the next free slot from now.`, [moveAction(at, true)]);
  }

  if (relative) {
    const anchor = anchorFor(target, ctx, session.id);
    if (!anchor || anchor.start === null) {
      return say(`I couldn't tell what “${target}” refers to. Try a time (“to 6pm”), or the name of another session or a meal.`);
    }
    if (prep === "after") {
      const at = roundUp5(anchor.name === "class" ? anchor.end : anchor.end);
      return say(`Putting ${what} after ${anchor.name} (from ${hhmm(at)}).`, [moveAction(at, true)]);
    }
    const startAt = (anchor.name === "class" ? anchor.start : anchor.start) - duration - 5;
    if (startAt < 0) return say(`There's no room before ${anchor.name} today.`);
    return say(`Putting ${what} before ${anchor.name} (at ${hhmm(startAt)}).`, [moveAction(startAt, false)]);
  }

  if (time) {
    const where = time.flexible ? `around ${hhmm(time.minutes)}` : `to ${hhmm(time.minutes)}`;
    return say(`Moving ${what} ${where}.`, [moveAction(time.minutes, time.flexible)]);
  }
  return null;
}

/** "postpone maths", "push reading back", "do maths tomorrow", "not today". */
function deferIntent(raw, l, ctx, history) {
  if (/\b(due|deadline)\b/.test(l)) return null;
  const verbCue = /^(?:please\s+|can you\s+|could you\s+)?(?:postpone|put off|delay|defer|snooze|push back|push)\b/.test(l);
  const phraseCue = /\b(not today|can'?t do (it|this|that|\w+) today|tomorrow instead|leave (it|this|that|\w+) for (tomorrow|later))\b/.test(l);
  const doCue = /^(?:i'?ll |i will |let me |can i |could i |i'?d like to |i want to |i'll do |)?(?:do|finish|start|work on|study|read|complete|continue)\b.*\b(tomorrow|(?:on|this|next) (?:mon|tue|wed|thu|fri|sat|sun)\w*|next week|this weekend|in \d+ days?)\b/.test(l);
  if (!verbCue && !phraseCue && !doCue) return null;

  const date = parseDate(l, ctx.today);
  const until = date && date.date > ctx.today ? date.date : addDays(ctx.today, 1);
  const tasks = openTasks(ctx);
  let task = null;
  const found = findBest(l, tasks);
  if (found && found.ambiguous) return ask(raw, "Which task do you mean?", found.ambiguous);
  if (found && found.item) task = found.item;
  if (!task) {
    const last = lastMentioned(history, ctx);
    if (last && last.session && last.session.kind === "TASK") task = ctx.tasks.find((t) => t.id === last.session.refId) || null;
    else if (last && last.task) task = last.task;
  }
  if (!task) return say("Which task should I put off? For example: “postpone maths homework to tomorrow”.");
  return deferReply(task, until, ctx);
}

/** "start maths today", "bring back reading", "do maths today". */
function startIntent(raw, l, ctx, history) {
  const cue = /^(?:please\s+|can you\s+)?(?:start|bring back|restore|resume|put back|un-?postpone)\b/.test(l) || /^(?:can i |let me |i'?ll |i will |i want to )?do\b.*\btoday\b/.test(l);
  if (!cue) return null;
  const found = findBest(l, openTasks(ctx));
  if (found && found.ambiguous) return ask(raw, "Which task do you mean?", found.ambiguous);
  if (!found || !found.item) return null;
  return say(`Bringing “${found.item.title}” back into today's plan.`, [{ type: "start_task", args: { taskId: found.item.id } }]);
}

module.exports = { scheduleIntent, deferIntent, startIntent, pickSubject, lastMentioned, focusTarget, anchorFor };
