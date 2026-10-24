// A free assistant: understands everyday language without any AI service.
//   interpret("move physics to 6", context, history) -> { reply, actions, clarify }
// It finds the task you mean by its words (so "mark maths homework done" works however you phrase it),
// remembers what you were just talking about ("move it to 7"), pulls out dates, times, durations and
// priorities, notices when a student is overwhelmed or low, and answers like a supportive study buddy.
// Anything it can't follow gets a helpful answer instead of a guess. With an AI key on the server the AI
// is used instead for freer conversation; this is what works with no key.
const { parseDate, parseDueTail, parseTime, parseDuration, label, hhmm, WEEKDAYS } = require("./dates");
const {
  fmtMinutes, capital, remove, findBest, say, ask, takeIds, idToken, noToken, pick, normalize, openTasks, plannedSessions, toMinutes, sessionMinutes,
} = require("./common");
const { supportIntent, smallTalkIntent } = require("./support");
const { fixTypos, plainText, expandSlang } = require("./typos");
const { scheduleIntent, deferIntent, startIntent, pickSubject, lastMentioned, focusTarget } = require("./schedule");

const EXAMPLES = [
  "add task maths homework 45 min due friday",
  "what's my plan today?",
  "move physics to 6pm   (or: swap maths and reading, put reading after dinner, move maths to tomorrow)",
  "I finished maths homework",
  "how am I doing this week?",
  "what should I focus on?",
  "I'm overwhelmed   (I'll help you shrink the day)",
  "add habit coding practice every day 45 min",
  "re-plan my day",
];

// ---------------------------------------------------------------------------- slot extraction
const LEVELS = { high: "High", medium: "Medium", normal: "Medium", low: "Low" };
const BY_NUMBER = { 1: "High", 2: "Medium", 3: "Low" };

/** Priority: a level word on either side of "priority" ("high priority", "priority: high", "p1"), or an urgency word. */
function readPriority(s) {
  let m;
  if ((m = /\b(high|medium|normal|low)[\s-]*(?:priority|prio|pri)\b/i.exec(s))) return { level: LEVELS[m[1].toLowerCase()], text: m[0] };
  if ((m = /\b(?:priority|prio|pri)\s*(?:is|of|to|:|=|-)?\s*(high|medium|normal|low)\b/i.exec(s))) return { level: LEVELS[m[1].toLowerCase()], text: m[0] };
  if ((m = /\b(?:priority|prio)\s*(?:is|:|=|-)?\s*([123])\b/i.exec(s))) return { level: BY_NUMBER[m[1]], text: m[0] };
  if ((m = /\bp([123])\b/i.exec(s))) return { level: BY_NUMBER[m[1]], text: m[0] };
  if ((m = /\b(top[\s-]*priority|very important|urgent|asap|critical|important)\b/i.exec(s))) return { level: "High", text: m[0] };
  return null;
}

// the words that introduce a deadline: everything after them is the date
const DUE_KEYWORD =
  /\b(?:the\s+)?(?:due(?:\s+date)?|deadline|submit(?:ted)?\s+(?:by|on)|submission(?:\s+date)?|finish\s+by|complete\s+by|needs?\s+to\s+be\s+(?:done|submitted|finished)\s+by)\b\s*(?:is|on|by|date|of|:|=|-)*\s*/i;

/**
 * Pulls priority, length and due date out of a sentence; what is left is the title.
 * "badDue" is set when someone wrote "due ..." but the date could not be read, so it can be asked about
 * instead of silently dropped.
 */
function extractSlots(raw, today, { dates = true } = {}) {
  let s = raw;
  const slots = {};
  let badDue = null;

  const priority = readPriority(s);
  if (priority) {
    slots.priority = priority.level;
    s = remove(s, priority.text);
  }

  const dur = parseDuration(s);
  if (dur) {
    slots.minutes = dur.minutes;
    s = remove(s, dur.text, ["for", "takes", "take", "about", "around", "of", "that takes"]);
  }

  if (dates) {
    const keyword = DUE_KEYWORD.exec(s);
    const tail = keyword ? s.slice(keyword.index + keyword[0].length) : "";
    const inTail = keyword ? parseDueTail(tail, today) : null;
    if (keyword && inTail) {
      slots.due = inTail.date;
      s = `${s.slice(0, keyword.index)} ${remove(tail, inTail.text)}`;
    } else if (keyword && tail.trim() && tail.trim().split(/\s+/).length <= 4) {
      badDue = tail.trim().replace(/[.,;!]+$/, "");
      s = s.slice(0, keyword.index);
    } else {
      const date = parseDate(s, today);
      if (date) {
        slots.due = date.date;
        s = remove(s, date.text, [
          "with a deadline of", "with a deadline on", "with a deadline", "with deadline of", "with deadline", "and deadline",
          "deadline of", "deadline is", "deadline on", "deadline by", "deadline", "due date is", "due date on", "due date",
          "submission on", "submission", "submit by", "submit on", "due on", "due by", "due", "by", "before", "on", "until", "till", "for", "from",
        ]);
      }
    }
  }

  // "... due fri high": a bare priority word at the end, once something else was understood
  if (!slots.priority && Object.keys(slots).length > 0) {
    const tail = /\s+(high|medium|low)\s*$/i.exec(s);
    if (tail) {
      slots.priority = capital(tail[1].toLowerCase());
      s = s.slice(0, tail.index);
    }
  }
  return { slots, rest: s, badDue };
}

// subjects and tools students write in lower case: "mlp project" is "MLP project"
const ACRONYMS = new Set(["os", "dbms", "dsa", "ml", "ai", "mlp", "cn", "coa", "toc", "oop", "oops", "sql", "html", "css", "cse", "ece", "eee", "api", "ui", "ux", "cpu", "gpu", "iot", "nlp", "cnn", "rnn", "ann", "daa", "sdlc", "tcp", "udp", "http", "pdf", "ppt", "cv", "gre", "gate", "cat", "jee", "neet"]);

function cleanTitle(s) {
  let t = s.replace(/\s+/g, " ").trim();
  // "i have a viva" / "i've got an assignment" -> "viva" / "assignment"
  t = t.replace(/^(?:i have to|i have|i've got to|i've got|i got|i must|i need)\s+(?:an?\s+|the\s+|my\s+)?/i, "");
  t = t.replace(/^(?:(?:a|an|the|new|called|named|that|to|for|please)\b[\s:,-]*)+/i, "");
  // "task read paper" -> "read paper"; but a title that IS "assignment" (or "reminder") stays
  t = t.replace(/^(?:(?:task|todo|to-do|to do)\b[\s:,-]+)+(?=\S)/i, "");
  // "add this for me: viva prep" / "add it: read paper"
  t = t.replace(/^(?:(?:this|it|that|these|those)\b(?:\s+for me)?[\s:,-]+)+(?=\S)/i, "");
  t = t.replace(/(?:[\s,;-]+(?:for|by|due|deadline|on|at|in|with|and|that takes|takes))+$/i, "");
  t = t.replace(/^[\s:,;.-]+|[\s:,;.-]+$/g, "").trim();
  t = t.replace(/[A-Za-z]+/g, (w) => (ACRONYMS.has(w) ? w.toUpperCase() : w));
  return capital(t).slice(0, 200);
}

// ------------------------------------------------------------------------------------ intents
function helpIntent(raw, l) {
  if (!/^(help( me)?( please)?|please help|what can you do|what can i ask|commands|examples?|how do (i|you)\b.*|how does this work|what do you do)\s*$/.test(l)) return null;
  return say(
    "I'm your planner assistant. I can add and change tasks and habits, mark things done, move or swap today's sessions, put a task off until tomorrow, show your plan, free time and a performance report, and help when things feel like too much. Just talk to me normally. For example:\n• " +
      EXAMPLES.join("\n• ")
  );
}

function replanIntent(raw, l) {
  if (!/\b(re-?plan|re-?shuffle|re-?do my (plan|day|schedule)|re-?organi[sz]e|re-?arrange|fix my (day|plan|schedule)|re-?schedule (my|the) (day|plan|schedule))\b/.test(l)) return null;
  return say("Re-planning the rest of today. Anything you moved by hand stays where it is.", [{ type: "replan", args: {} }]);
}

function reportIntent(raw, l, ctx) {
  if (isCommand(l)) return null; // "add task stress test report" is a command, not a question
  // a task whose own name contains "report" or "summary" is about that task, not about the performance report
  const aboutATask = findBest(l, openTasks(ctx));
  const keywords = !(aboutATask && (aboutATask.item || aboutATask.ambiguous)) &&
    /\b(report|summary|stats|statistics|performance|analysis|analy[sz]e|grades?|score|streaks?|productiv\w*|my progress|show (me )?(my )?progress)\b/.test(l);
  const cue =
    keywords ||
    /\bhow (am i|have i been|did i|was my|is my|are my|were my)\b.*\b(doing|going|week|day|month|study|studying|habits?|work|progress|results?)\b/.test(l) ||
    /\bhow('?s| is) (my|the) (week|month|progress|studying)\b/.test(l) ||
    /^how am i doing\b/.test(l);
  if (!cue || /\blog (my )?progress\b/.test(l)) return null;
  let days = 7;
  const n = /\b(\d{1,2})\s*days?\b/.exec(l);
  if (n) days = Math.min(60, Math.max(1, Number(n[1])));
  else if (/\b(today|daily|so far today|this morning|my day|the day|this day|day going|day went)\b/.test(l)) days = 1;
  else if (/\bmonth(ly)?\b/.test(l)) days = 30;
  else if (/\b(two weeks|2 weeks|fortnight)\b/.test(l)) days = 14;
  const lead = pick(["Let me pull that together.", "Sure, here's the honest picture.", "Okay, let's look at the numbers."], l);
  return say(lead, [{ type: "show_report", args: { days } }]);
}

/**
 * "What should I focus on?" in all the ways people really ask it: "what to focus on", "what do I do now",
 * "where to start", "which one first", "my priorities", "most urgent", "I don't know where to start"...
 */
const FOCUS_ASK = new RegExp(
  [
    "\\bfocus(ing)?\\b",
    "\\bwhat (should|can|could|shall|do|will) i (be )?(do|doing|work on|working on|study|studying|start|tackle|begin|handle|take up)\\b",
    "\\bwhat (do|should) i need to (do|work on|study|finish|tackle|start)\\b",
    "\\bwhat to (do|work on|study|start|begin|tackle|handle)\\b",
    "\\bwhere (to|do i|should i|can i|shall i) (start|begin)\\b",
    "\\bwhich (task|one|thing|subject|assignment|work)\\b.*\\b(first|start|begin|tackle|work on|do)\\b",
    "\\b(my|the|top|what'?s|what are|what is) (priority|priorities)\\b",
    "^(priority|priorities)$",
    "\\bprioriti[sz]e\\b",
    "\\b(most|more) (important|urgent|pressing)\\b",
    "^(the )?(urgent|important|pressing)( task| thing| one)?s?$",
    "\\bwhat('?s| is| are) (the )?(important|urgent|pressing|first)\\b",
    "^what first$",
    "^what (next|now)$",
    "\\bstart with what\\b",
    "\\b(don'?t|do not|dont) know (what to do|where to start|what to start|what to work on|what first)\\b",
    "\\b(tell|suggest|give|show|recommend) me (something|what|where|a task|a thing)\\b.*\\b(do|start|work|study|first)\\b",
    "\\bsomething to do\\b",
    "\\bwhat needs my attention\\b",
    "\\bwhat'?s on my plate\\b",
  ].join("|")
);

function focusIntent(raw, l, ctx) {
  // a command is a command, however politely it is put ("can you please add a low priority task...")
  if (/\b(add|create|insert|remind me|delete|remove|mark|move|swap|rename|change|set|postpone|skip|reschedule|push|bring back)\b/.test(l) || /^(todo|task|new)\b/.test(l)) return null;
  // "I can't focus", "I need to focus on dsa": a statement about themselves, not a question
  if (/^(i|i'm|im|i am|i can't|i cant|my)\b/.test(l) && !/\b(don'?t|do not|dont) know (what|where)\b/.test(l)) return null;
  // about another day: that is a question about the plan, which another part answers
  if (/\b(tomorrow|yesterday|next week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b/.test(l)) return null;
  if (!FOCUS_ASK.test(l)) return null;
  // a sentence that names one of their tasks is about that task
  const named = ctx ? findBest(l, openTasks(ctx)) : null;
  if (named && (named.item || named.ambiguous)) return null;
  return say(pick(["Here's where I'd start.", "Good question. This is what matters most right now.", "Let's keep it simple. Start here:"], l), [{ type: "show_focus", args: {} }]);
}

function lightenIntent(raw, l) {
  const cue =
    /\b(lighten|lighter|do less|take (some|things|stuff|a few things) off my plate|make (today|my day|it) (easier|lighter|shorter|smaller|less)|cut (down|back)( on)? (today|my plan|my day|the plan)|ease (up )?(today|my day|my plan)|reduce (today|my (workload|load|plan)))\b/.test(l) ||
    /\btoo much\b.*\b(today|plan)\b/.test(l) ||
    /\b(only|just)\s+(have|got)\b.*\b(today|left|free|tonight)\b/.test(l);
  if (!cue) return null;
  const budget = /\b(only|just)\b/.test(l) ? parseDuration(l) : null;
  const args = budget ? { keepMinutes: Math.min(600, Math.max(30, budget.minutes)) } : {};
  return say(
    "Okay, let's make today lighter. I'll keep the most urgent work, and show you exactly what would move to tomorrow before anything changes.",
    [{ type: "lighten_day", args }]
  );
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

/** Questions about the student's own data that can be answered straight from what the app sent. */
function dataIntent(raw, l, ctx) {
  if (/^(what'?s|what is|whats)\s+(my\s+)?(next|up next|coming up)\b|^(my )?next (session|task|thing)$|^what'?s coming up\b/.test(l)) {
    const next = plannedSessions(ctx).slice().sort((a, b) => toMinutes(a.start) - toMinutes(b.start)).find((s) => toMinutes(s.start) > (ctx.nowMin ?? 0));
    return say(next
      ? `Next up: “${next.title}” from ${next.start} to ${next.end} (${fmtMinutes(sessionMinutes(next))}).`
      : "Nothing else is planned for today. Enjoy the free time, or say “what should I focus on?” if you'd like to get ahead.");
  }
  if (/^(what time|when)\b.*\b(session|start|is my|is the|do i (start|have)|am i)\b/.test(l) && !/\bdue\b/.test(l)) {
    const session = findBest(l, plannedSessions(ctx));
    if (session && session.item) return say(`“${session.item.title}” is planned from ${session.item.start} to ${session.item.end} today.`);
    const task = findBest(l, openTasks(ctx));
    if (task && task.item) return say(`“${task.item.title}” isn't planned for today.` + (task.item.due ? ` It's due ${label(task.item.due)}.` : ""));
  }
  if (/\bhow many (open |pending )?(tasks?|things|to-?dos?)\b/.test(l)) {
    const open = openTasks(ctx);
    const overdue = open.filter((t) => t.due && t.due < ctx.today).length;
    return say(open.length
      ? `You have ${open.length} open task${open.length === 1 ? "" : "s"}${overdue ? `, ${overdue} of them overdue` : ""}.`
      : "You have no open tasks right now.");
  }
  if (/\bwhen\b.*\b(is|are|does)\b.*\bdue\b|\bwhen is\b.*\bdeadline\b/.test(l)) {
    const found = findBest(l, openTasks(ctx));
    if (found && found.item) return say(found.item.due ? `“${found.item.title}” is due ${label(found.item.due)}.` : `“${found.item.title}” has no due date.`);
    if (found && found.ambiguous) return ask(raw, "Which task do you mean?", found.ambiguous);
  }
  if (/\bhow long (is|does|will|would)\b/.test(l)) {
    const found = findBest(l, openTasks(ctx));
    if (found && found.item) {
      const left = Math.max(0, (found.item.minutes || 0) - (found.item.spent || 0));
      return say(`“${found.item.title}” is estimated at ${fmtMinutes(found.item.minutes || 0)}` + (found.item.spent ? `, with ${fmtMinutes(left)} still to go.` : "."));
    }
  }
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

// "please", "can you", "i want to", "i'd like to": how people lead into a request
const ASKING = "(?:(?:please|kindly|can you|could you|would you|will you|i want to|i wanna|want to|wanna|i'd like to|i would like to|i'd love to|i need to|need to|i have to|have to|i'm going to|let's|let me|lemme|just|quickly|go ahead and|help me)\\s+)*";
const WANTING = "(?:(?:please|kindly|can you|could you|would you|will you|i want to|i wanna|i'd like to|i would like to|i'd love to|let's|let me|lemme|just|quickly|go ahead and|help me)\\s+)*";
const PRIORITY_WORD = "(?:(?:high|medium|low|normal)[\\s-]*prio(?:rity)?|urgent|important|critical)";
const TASK_WORD = "(?:task|todo|to-do|to do|reminder|assignment)";

function addTaskIntent(raw, l, ctx) {
  let body = null;
  let m;
  // "add a high priority task MLP project due friday", "I want to create a new task: ..."
  if ((m = new RegExp(`^${ASKING}(?:add|create|make|new|put|set up|start)\\s+(?:a\\s+|an\\s+|the\\s+|another\\s+)?(?:new\\s+)?(${PRIORITY_WORD}\\s+)?${TASK_WORD}s?\\b[\\s:,-]*(.*)$`, "i").exec(raw))) {
    body = `${m[1] || ""}${m[2]}`;
  }
  // "add MLP project as a high priority task due friday", "add reading to my to-do list"
  else if ((m = new RegExp(`^${WANTING}(?:add|create|put)\\s+(.+?)\\s+(?:as|to|into|in)\\s+(?:a\\s+|an\\s+|my\\s+|the\\s+)?(${PRIORITY_WORD}\\s+)?(?:to-?do list|todo list|task list|tasks|list|${TASK_WORD})\\b(.*)$`, "i").exec(raw))) {
    body = `${m[1]} ${m[2] || ""} ${m[3] || ""}`;
  }
  // "add MLP project high priority"
  else if ((m = new RegExp(`^${WANTING}(?:add|create|new)\\s+(.+)$`, "i").exec(raw))) body = m[1];
  // "remind me to ...", "I have to ...", "don't forget to ..."
  else if ((m = /^(?:i need to|i have to|i must|i should|i got to|i've got to|ive got to|remind me to|don't forget to|dont forget to)\s+(.+)$/i.exec(raw))) body = m[1];
  else if ((m = /^(?:todo|task)[:\s]+(.+)$/i.exec(raw))) body = m[1];
  if (body === null) return null;

  const { slots, rest, badDue } = extractSlots(body, ctx.today);
  const title = cleanTitle(rest);
  if (!title || /^(?:\d+|a few|some|two|three|four|five)\s+(?:new\s+)?tasks?$/i.test(title) || /^(?:new\s+)?tasks?$/i.test(title)) {
    return say("What should the task be called? I add them one at a time. For example: “add task maths homework 45 min due friday”.");
  }
  if (badDue) return askAboutDue(title, slots, badDue);
  return addTaskReply(l, title, slots, ctx);
}

/** The sentence that adds this task again, for buttons. */
function addText(title, slots) {
  return `add task ${title}` + (slots.minutes ? ` ${slots.minutes} min` : "") + (slots.priority ? ` ${slots.priority} priority` : "") + (slots.due ? ` due ${slots.due}` : "");
}

/** Someone wrote "due ..." but the date can't be read: ask, don't drop it quietly. */
function askAboutDue(title, slots, badDue) {
  const question = `I couldn't read the due date “${badDue}”. When is “${title}” due?`;
  return say(question, [], {
    question,
    options: [
      { label: "Due tomorrow", text: addText(title, { ...slots, due: undefined }) + " due tomorrow" },
      { label: "Add without a due date", text: addText(title, { ...slots, due: undefined }) },
    ],
  });
}

function addTaskReply(l, title, slots, ctx) {
  // the same name again: ask, instead of quietly making a twin or quietly changing the old one
  const same = ctx && !/\banother\b/i.test(l) && ctx.tasks.find((t) => !t.completed && t.title.trim().toLowerCase() === title.toLowerCase());
  if (same) {
    const question = `You already have “${same.title}”${same.due ? ` (due ${label(same.due)})` : ""}. Should I update that one, or add another?`;
    const options = [];
    if (slots.due) options.push({ label: `Update its due date to ${label(slots.due)}`, text: `change ${same.title} due date to ${slots.due}` });
    if (slots.priority) options.push({ label: `Set it to ${slots.priority.toLowerCase()} priority`, text: `set ${same.title} to ${slots.priority.toLowerCase()} priority` });
    options.push({ label: "Add another one", text: addText(title, slots).replace(/^add task/, "add another task") });
    return say(question, [], { question, options });
  }
  const args = { title, ...(slots.minutes ? { minutes: slots.minutes } : {}), ...(slots.priority ? { priority: slots.priority } : {}), ...(slots.due ? { due: slots.due } : {}) };
  const details = [slots.minutes ? fmtMinutes(slots.minutes) : null, slots.due ? `due ${label(slots.due)}` : null, slots.priority ? `${slots.priority.toLowerCase()} priority` : null].filter(Boolean);
  const lead = pick(["Sure, adding", "Got it, adding", "Okay, adding"], l);
  return say(`${lead} the task “${title}”${details.length ? ` (${details.join(", ")})` : ""}.`, [{ type: "add_task", args }]);
}

const QUESTION_START = /^(what|when|where|why|how|who|which|can|could|do|does|did|is|are|was|were|will|would|should|am|has|have i|haven't i)\b/;

/**
 * A plain statement with a due date, a length or a priority and no command ("exam prep 2 hours tomorrow",
 * "i have a viva on monday", "submit mlp project tomorrow") is a task. If a task with those words already
 * exists, it asks instead of guessing.
 */
function bareTaskIntent(raw, l, ctx) {
  if (/\?\s*$/.test(raw) || QUESTION_START.test(l) || l.split(" ").length < 2) return null;
  const { slots, rest } = extractSlots(raw, ctx.today);
  if (!slots.minutes && !slots.due && !slots.priority) return null;
  const title = cleanTitle(rest);
  if (title.length < 3 || /^(?:\d+|a few|some)\s+tasks?$/i.test(title)) return null;

  const existing = findBest(title, openTasks(ctx));
  if (existing && existing.item) {
    const t = existing.item.title;
    const options = [];
    if (slots.due) options.push({ label: `Change “${t}” due date to ${label(slots.due)}`, text: `change ${t} due date to ${slots.due}` });
    if (slots.minutes) options.push({ label: `Make “${t}” ${fmtMinutes(slots.minutes)}`, text: `make ${t} ${slots.minutes} minutes` });
    if (slots.priority) options.push({ label: `Set “${t}” to ${slots.priority.toLowerCase()} priority`, text: `set ${t} to ${slots.priority.toLowerCase()} priority` });
    options.push({ label: "Add it as a new task", text: `add task ${raw}` });
    const question = `You already have “${t}”. Should I change that one, or add this as a new task?`;
    return say(question, [], { question, options });
  }
  return addTaskReply(l, title, slots, ctx);
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
  if (task && task.ambiguous) return ask(raw, "Which task do you mean?", task.ambiguous);
  if (!task || !task.item) return null;
  return say(`Noted: ${fmtMinutes(dur.minutes)} on “${task.item.title}”.`, [{ type: "log_progress", args: { taskId: task.item.id, minutes: dur.minutes } }]);
}

function completeIntent(raw, l, ctx, history) {
  // "how did i do today", "how much have i done", "did i finish maths?" are questions, not "I finished"
  if (/\?\s*$/.test(raw) || QUESTION_START.test(l)) return null;
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
  if (task && task.item) return say(pick([`Nice work. Marking “${task.item.title}” as done.`, `Marking “${task.item.title}” as done. Well done.`], l), [{ type: "complete_task", args: { taskId: task.item.id } }]);
  if (habit && habit.item) return say(`Marking “${habit.item.title}” as done for today. Keep it going.`, [{ type: "complete_habit", args: { habitId: habit.item.id } }]);
  // "I finished it" / "done with that one": the thing we were just talking about
  if (/\b(it|that|this|that one|this one)\b/.test(l)) {
    const last = lastMentioned(history, ctx);
    const taskId = last && (last.session ? (last.session.kind === "TASK" ? last.session.refId : null) : last.task && last.task.id);
    const t = taskId && ctx.tasks.find((x) => x.id === taskId);
    if (t) return say(`Marking “${t.title}” as done. Well done.`, [{ type: "complete_task", args: { taskId: t.id } }]);
  }
  return say("I couldn't tell which task you finished. Say “show my tasks” to see them.");
}

function skipIntent(raw, l, ctx, history) {
  if (!/^(?:please\s+)?(?:skip|postpone|push back|snooze)\b|\blater\b/.test(l)) return null;
  const sessions = plannedSessions(ctx);
  const session = findBest(l, sessions);
  if (session && session.ambiguous) return ask(raw, "Which session do you mean?", session.ambiguous);
  if (session && session.item) return say(`Skipping “${session.item.title}”: I'll plan it again for later today.`, [{ type: "skip_session", args: { sessionId: session.item.id } }]);
  if (/\b(it|this|that|this one|that one)\b/.test(l)) {
    const last = lastMentioned(history, ctx);
    if (last && last.session) return say(`Skipping “${last.session.title}”: I'll plan it again for later today.`, [{ type: "skip_session", args: { sessionId: last.session.id } }]);
  }
  return say("I couldn't find that among today's planned sessions. Say “what's my plan today?” to see them.");
}

/** change / rename / make: figures out which kind of change from the words used. */
function modifyIntent(raw, l, ctx, history) {
  // "remove the due date from X", "clear the deadline of X"
  if (/^(?:please\s+)?(?:remove|clear|delete|drop)\b.*\b(due date|due|deadline)\b/.test(l)) {
    const task = findBest(l, openTasks(ctx));
    if (task && task.ambiguous) return ask(raw, "Which task do you mean?", task.ambiguous);
    if (task && task.item) return say(`Removing the due date from “${task.item.title}”.`, [{ type: "update_task", args: { taskId: task.item.id, due: "none" } }]);
    return say("I couldn't find that task. Say “show my tasks” to see them.");
  }

  const verb = /^(?:please\s+)?(?:can you\s+)?(move|shift|reschedule|schedule|put|set|change|update|rename|make|edit|extend|shorten|do)\b/.exec(l);
  // "mlp project priority low", "dbms due monday": no command word, but clearly a change to a task
  if (!verb && (!/\b(priority|due|deadline)\b/.test(l) || QUESTION_START.test(l) || /\b(add|create|note down|remind me)\b/.test(l))) return null;

  const time = parseTime(l, ctx.nowMin);
  const duration = parseDuration(l);
  const date = parseDate(l, ctx.today);
  const tasks = openTasks(ctx);

  // rename "X to Y"
  if (verb && /\brename\b|\bchange\b.*\b(name|title)\b/.test(l)) {
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
  if (verb && time && !/\b(due|deadline)\b/.test(l)) {
    if (date && date.date !== ctx.today) return say("I can only move today's sessions. To put a task off until another day, say “postpone it to friday”.");
    const session = findBest(l, plannedSessions(ctx));
    if (session && session.ambiguous) return ask(raw, "Which session do you mean?", session.ambiguous);
    if (session && session.item) {
      const where = time.flexible ? `around ${hhmm(time.minutes)}` : `to ${hhmm(time.minutes)}`;
      return say(`Moving “${session.item.title}” ${where}.`, [{ type: "move_session", args: { sessionId: session.item.id, time: hhmm(time.minutes), flexible: time.flexible } }]);
    }
    const task = findBest(l, tasks);
    if (task && task.item) return say(`“${task.item.title}” isn't planned for today. Say “move ${task.item.title} to 6pm” and I'll schedule it, or “do it tomorrow”.`);
    return say("I couldn't find that among today's planned sessions. Say “what's my plan today?” to see them.");
  }

  // a new length
  if (verb && duration && /\b(make|change|set|extend|shorten|update|edit)\b/.test(l) && !/\b(due|deadline)\b/.test(l)) {
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
      if (date && !/\b(due|deadline|date)\b/.test(l)) {
        // "change maths to friday": the deadline, or just when to do it?
        const q = `Do you want to change the due date of “${task.item.title}” to ${label(date.date)}, or just do it from then?`;
        return say(q, [], { question: q, options: [
          { label: "Change the due date", text: `change ${task.item.title} due date to ${date.date}` },
          { label: "Just do it from then", text: `postpone ${task.item.title} to ${date.date}` },
        ] });
      }
      if (date) return say(`Setting “${task.item.title}” due ${label(date.date)}.`, [{ type: "update_task", args: { taskId: task.item.id, due: date.date } }]);
      return say(`When should “${task.item.title}” be due? For example: “friday”.`);
    }
    // without a command word this may simply be a new task ("i have an assignment due friday")
    return verb ? say("I couldn't find that task. Say “show my tasks” to see them.") : null;
  }
  return null;
}


// ============================================================================ keywords anywhere
// The careful patterns above come first. When none of them fits, these look for KEYWORDS ANYWHERE in the sentence,
// so extra words, odd word order and different phrasing don't matter. Anything that deletes still needs a "yes".

/** "...add it: MLP project high priority due tomorrow", "please add MLP project", "put MLP project on my list". */
function looseAddIntent(raw, l, ctx) {
  if (/\?\s*$/.test(raw) || QUESTION_START.test(l) || /\bhabit\b/.test(l)) return null;
  const verb = /\b(add|create|insert|enter|note down|jot down|remind me to|remember to|don't forget to|todo|to-do)\b/i.exec(raw);
  if (!verb) return null;

  // drop the asking-words and the word "task"; the rest is the title plus its details
  let body = raw.replace(verb[0], " ");
  body = body
    .replace(/\b(please|kindly|can you|could you|would you|will you|i want to|i wanna|i'd like to|i would like to|i need you to|for me|go ahead and|it|this|that)\b/gi, " ")
    .replace(/\b(a|an|the|new|my)?\s*(task|todo|to-do|reminder|assignment)s?\b\s*(called|named)?/gi, (m, art) => (/assignment/i.test(m) ? m : " "))
    .replace(/\b(to|into|in|on)\s+(my\s+)?(tasks?|to-?do(\s+list)?|list)\b/gi, " ");
  const { slots, rest, badDue } = extractSlots(body, ctx.today);
  const title = cleanTitle(rest);
  if (!title || title.length < 2) return say("What should the task be called? For example: “add task maths homework 45 min due friday”.");
  if (badDue) return askAboutDue(title, slots, badDue);
  return addTaskReply(l, title, slots, ctx);
}

/** The report, asked any way at all: "how i am doing", "progress", "am i on track", "how much did i study this week". */
function looseReportIntent(raw, l, ctx) {
  if (isCommand(l)) return null;
  const words = /\b(report|summary|summari[sz]e|stats?|statistics|performance|performing|progress|analysis|analy[sz]e|grades?|score|streaks?|productiv\w*|on track|doing (well|ok|okay|good|bad|fine|great))\b/;
  const how = /\bhow\b.*\b(am i|i am|are my|have i|did i|was my|were my|is my|has my|much did i|much have i|much do i)\b/;
  const howMuch = /\bhow much\b.*\b(done|studied|study|worked|work|completed|finished|learn\w*|revised|revise)\b/;
  const tracking = /\b(am i|are we)\b.*\b(on track|behind|ahead|falling behind|keeping up)\b/;
  const period = /\bhow\b.*\b(was|is|were|went|going)\b.*\b(this|the|last|my)\b.*\b(week|day|month|today|term|semester)\b/;
  if (!words.test(l) && !how.test(l) && !howMuch.test(l) && !tracking.test(l) && !period.test(l)) return null;
  // "how long is X" and "when is X due" are questions about a task, handled earlier; a task's own name isn't a report
  const aboutATask = findBest(l, openTasks(ctx));
  if (aboutATask && (aboutATask.item || aboutATask.ambiguous) && !/\b(how am i|how i am|my progress|report)\b/.test(l)) return null;

  let days = 7;
  const n = /\b(\d{1,2})\s*days?\b/.exec(l);
  if (n) days = Math.min(60, Math.max(1, Number(n[1])));
  else if (/\b(today|daily|so far today|this morning|my day|the day|this day|day going|day went)\b/.test(l)) days = 1;
  else if (/\bmonth(ly)?\b/.test(l)) days = 30;
  else if (/\b(two weeks|2 weeks|fortnight)\b/.test(l)) days = 14;
  const lead = pick(["Let me pull that together.", "Sure, here's the honest picture.", "Okay, let's look at the numbers."], l);
  return say(lead, [{ type: "show_report", args: { days } }]);
}

/** A task is named but nothing says what to do with it: offer the sensible things instead of "I didn't get that". */
function mentionIntent(raw, l, ctx) {
  const found = findBest(l, openTasks(ctx));
  if (!found || !found.item) return null;
  const title = found.item.title;
  if (l.split(" ").length > title.split(" ").length + 4) return null; // a long, unrelated sentence
  const question = `What would you like to do with “${title}”?`;
  return say(question, [], {
    question,
    options: [
      { label: "Mark it done", text: `mark ${title} done` },
      { label: "Move its session", text: `move ${title}` },
      { label: "Do it tomorrow", text: `move ${title} to tomorrow` },
      { label: "Delete it", text: `delete ${title}` },
    ],
  });
}

const COMMANDISH = /^(add|create|new|remind me|delete|remove|mark|move|swap|rename|change|set|postpone|skip|todo|task)\b/;
// "i need to add ...", "can you move ...": the request is what comes after the lead-in
const LEAD_IN = new RegExp(`^${ASKING}`, "i");
const isCommand = (l) => COMMANDISH.test(l) || COMMANDISH.test(l.replace(LEAD_IN, ""));

const INTENTS = [
  // how the student feels comes first: a crisis message is never treated as a command
  (raw, l, ctx) => {
    if (/^(am i|are we|how am i|how are my)\b.*\b(behind|on track|ahead|keeping up)\b/.test(l)) return null;
    const r = supportIntent(raw, l, ctx);
    if (!r) return null;
    const crisis = /(112|Tele-MANAS)/.test(r.reply) && /sorry you're feeling this much pain/.test(r.reply);
    return crisis || !isCommand(l) ? r : null;
  },
  helpIntent, smallTalkIntent, replanIntent, reportIntent, focusIntent, lightenIntent,
  queryIntent, dataIntent,
  addHabitIntent, addTaskIntent, deleteIntent, progressIntent, completeIntent,
  startIntent, scheduleIntent, deferIntent, skipIntent, modifyIntent,
  looseReportIntent, looseAddIntent, bareTaskIntent, mentionIntent,
];

const FALLBACKS = [
  "I'm not sure I caught that. Here are some things I understand:",
  "Hmm, I didn't quite get that one. Try something like:",
];

/** "ok. can you move..." / "actually, swap it with..." / "hey, add task...": the filler at the start is dropped. */
const FILLERS = /^(?:(?:ok|okay|so|well|hmm+|um+|uh+|actually|also|then|alright|yeah|yep|sure|oh|btw|and|but|hey|hi)\b[\s,.:;!-]*)+/i;
function withoutFillers(text) {
  const stripped = text.replace(FILLERS, "").trim();
  return stripped || text; // "ok" or "hey" on its own is still a message
}

// ======================================================================= continuing a conversation
// A reply like "9pm" only makes sense after "Where should I move it?". The assistant's last question tells it
// what the short reply is about, and the sentence is completed before it is understood.
const COMMAND_START = /^(add|create|new|move|shift|swap|delete|remove|mark|set|change|rename|skip|postpone|push|schedule|start|show|what|how|when|who|where|why|help|re-?plan|lighten|i|i'm|remind|can|could|please|let's)\b/;
const DATE_OR_TIME = /\b(tomorrow|today|tonight|morning|afternoon|evening|night|noon|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun|next week|weekend)\b|\d/;

function followUp(raw, l, past) {
  const last = past[past.length - 1];
  if (!last || last.role !== "assistant") return null;
  const q = String(last.text || "");
  const words = l.split(" ").length;
  let m;

  // "What would you like to do with it?": a word like "delete" or "postpone" IS the answer
  if (words <= 3 && (m = /What would you like to do with “(.+?)”\?/.exec(q))) {
    if (/^(done|finished|finish|complete\w*)\b/.test(l)) return `mark ${m[1]} done`;
    if (/^(delete|remove)\b/.test(l)) return `delete ${m[1]}`;
    if (DATE_OR_TIME.test(l)) return `move ${m[1]} to ${raw}`;
    return `${raw} ${m[1]}`;
  }

  if (words > 7 || COMMAND_START.test(l)) return null;
  const before = past[past.length - 2];
  const prevUser = before && before.role === "user" ? String(before.text || "") : null;

  if ((m = /Where should I move “(.+?)”\?/.exec(q))) return `move ${m[1]} ${/^(to|at|after|before|around|for|in|until|till|by)\b/.test(l) ? "" : "to "}${raw}`;
  if ((m = /When should “(.+?)” be due\?/.exec(q))) return `change ${m[1]} due date to ${raw}`;
  if ((m = /How often should “(.+?)” happen\?/.exec(q))) return `add habit ${m[1]} ${raw}`;
  if ((m = /couldn't read the due date .*?When is “(.+?)” due\?/.exec(q))) return `add task ${m[1]} due ${raw}`;
  if (/What should the task be called\?/.test(q) && prevUser) return `${takeIds(prevUser).clean} ${raw}`;
  if (/Which (?:task|one|session|habit) do you mean\?/.test(q)) {
    const origin = askedAbout(past);
    if (origin) return `${takeIds(origin.text).clean} ${raw}`;
  }
  if ((m = /What would you like to do with “(.+?)”\?/.exec(q))) {
    if (/^(done|finished|finish|complete\w*)\b/.test(l)) return `mark ${m[1]} done`;
    if (/^(delete|remove)\b/.test(l)) return `delete ${m[1]}`;
    if (DATE_OR_TIME.test(l)) return `move ${m[1]} to ${raw}`;
    return `${raw} ${m[1]}`;
  }
  return null;
}

// "it", "this one", "that": the task you were just talking about
const COMMAND_VERB = /\b(move|shift|reschedule|schedule|put|set|make|change|rename|delete|remove|cancel|mark|skip|postpone|push|delay|defer|do|start|finish|finished|complete|completed|done|swap|switch|bring|extend|shorten|update|edit|worked|spent|log)\b/;
const NOT_A_PRONOUN = /^\s+(week|weekend|month|morning|afternoon|evening|night|noon|today|tonight|tomorrow|time|year|semester|term|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun)\b/i;

/** The task being talked about: what the app says the last action touched, else the last one named in the chat. */
function topicTask(ctx, past) {
  if (ctx.focus) return { id: ctx.focus.taskId, title: ctx.focus.title };
  const last = lastMentioned(past, ctx);
  if (last && last.session && last.session.kind === "TASK") return { id: last.session.refId, title: last.session.title };
  if (last && last.task) return { id: last.task.id, title: last.task.title };
  return null;
}

/** "move it to 9pm" -> "move MLP Project to 9pm", when "it" can only mean the task you were just talking about. */
function resolvePronouns(raw, l, ctx, past) {
  if (/\b(add|create|insert|note down|remind me)\b/.test(l) || QUESTION_START.test(l) || /\?\s*$/.test(raw)) return null;
  if (!COMMAND_VERB.test(l)) return null;
  const named = findBest(l, openTasks(ctx));
  if (named && (named.item || named.ambiguous)) return null; // the sentence already says which task
  const re = /\b(it|this one|that one|the same one|this|that)\b/gi;
  let found = null;
  let m;
  while ((m = re.exec(raw))) {
    if (NOT_A_PRONOUN.test(raw.slice(m.index + m[0].length))) continue; // "this week", "that evening"
    found = m;
    break;
  }
  if (!found) return null;
  const topic = topicTask(ctx, past);
  if (!topic) return null;
  return raw.slice(0, found.index) + topic.title + raw.slice(found.index + found[0].length);
}

// ======================================================================= choosing between look-alikes
const WHICH = /Which (?:task|one|session|habit) do you mean\?/;
const ORDINAL = { first: 0, "1st": 0, "1": 0, one: 0, second: 1, "2nd": 1, "2": 1, two: 1, third: 2, "3rd": 2, "3": 2, three: 2 };
const WEEKDAY3 = { monday: "mon", tuesday: "tue", wednesday: "wed", thursday: "thu", friday: "fri", saturday: "sat", sunday: "sun" };
const MONTH3 = { january: "jan", february: "feb", march: "mar", april: "apr", june: "jun", july: "jul", august: "aug", september: "sep", sept: "sep", october: "oct", november: "nov", december: "dec" };

/** Which of the offered choices the reply means: "first", "2", "the last one", "the one due thursday", "30th". */
function choiceIndex(l, options) {
  const w = l.replace(/^(the|option|number|no\.?|#)\s+/, "").replace(/\s+(one|option)$/, "").trim();
  if (Object.prototype.hasOwnProperty.call(ORDINAL, w) && ORDINAL[w] < options.length) return ORDINAL[w];
  if (/^(the )?last( one)?$/.test(l)) return options.length - 1;

  // by what differs: the due day or the time, as the choices show it
  const words = l
    .replace(/\b(the|one|due|on|by|with|that|is|it|a|an|please)\b/g, " ")
    .split(/[^a-z0-9]+/)
    .filter(Boolean)
    .map((x) => x.replace(/^(\d+)(st|nd|rd|th)$/, "$1"))
    .map((x) => WEEKDAY3[x] || MONTH3[x] || x);
  if (!words.length) return null;
  const matches = options
    .map((o, i) => ({
      i,
      // what follows the name, without lengths like "1 h 30 min" (their numbers would be taken for days)
      detail: o.label.split(" \u00b7 ").slice(1).filter((part) => !/^\d+ ?(h|min)\b/.test(part)).join(" ").toLowerCase().split(/[^a-z0-9]+/).filter(Boolean),
    }))
    .filter((c) => c.detail.length && words.every((x) => c.detail.includes(x)));
  return matches.length === 1 ? matches[0].i : null;
}

/**
 * The student answers "Which one do you mean?" with words instead of a tap. If the answer picks one of the choices
 * (or says nothing new, which would only repeat the question), it is settled here, not glued onto the sentence again.
 */
/** The sentence that started a "Which one do you mean?" (it may have been asked several times in a row). */
function askedAbout(past) {
  let origin = null;
  for (let i = past.length - 1; i >= 1; i -= 2) {
    if (past[i].role === "assistant" && WHICH.test(String(past[i].text || "")) && past[i - 1].role === "user") origin = past[i - 1];
    else break;
  }
  return origin;
}

function resolveChoice(raw, l, past, context) {
  const before = askedAbout(past);
  if (!before) return null;
  if (l.split(" ").length > 7 || COMMAND_START.test(l)) return null;

  const again = interpret(String(before.text || ""), context, []);
  const options = again.clarify && again.clarify.options;
  if (!options || !options.length) return null;

  const index = choiceIndex(l, options);
  if (index !== null) return interpret(options[index].text, context, []);

  // the name of one of the choices, typed: "physics homework"
  const named = findBest(l, options.map((o, i) => ({ title: o.label.split(" \u00b7 ")[0], i })));
  if (named && named.item) return interpret(options[named.item.i].text, context, []);

  const known = new Set(takeIds(before.text).clean.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean));
  const fresh = l.split(/[^a-z0-9]+/).filter((w) => w && !known.has(w));
  if (fresh.length === 0) return say(`That doesn't tell them apart.\n${again.reply}`, [], again.clarify);
  return null; // it adds something new: the old way (put it into the sentence) still applies
}

/** Other tasks (and habits) that have exactly the name of the chosen ones. */
function sameNameAs(context, ids) {
  const c = context || {};
  const items = [...(c.tasks || []), ...(c.habits || [])];
  const sessions = (c.plan && c.plan.sessions) || [];
  const out = [];
  for (const id of ids) {
    const session = sessions.find((x) => x.id === id);
    const chosen = items.find((t) => t.id === (session ? session.refId : id));
    if (!chosen) continue;
    for (const t of items) {
      if (t.id !== chosen.id && String(t.title).trim().toLowerCase() === String(chosen.title).trim().toLowerCase()) out.push(t.id);
    }
  }
  return out;
}

/**
 * For this one sentence, the items the student did NOT choose are given names that match nothing, so the chosen one is
 * the only thing the sentence can mean (whatever the sentence looks like, and even if two have the same name).
 */
function isolate(context, excluded) {
  const c = context || {};
  const tasks = (c.tasks || []).map((t) => ({ ...t }));
  const habits = (c.habits || []).map((h) => ({ ...h }));
  const plan = c.plan ? { ...c.plan, sessions: (c.plan.sessions || []).map((x) => ({ ...x })) } : c.plan;
  const sessions = plan ? plan.sessions : [];
  let n = 0;
  for (const id of excluded) {
    const hidden = `qzx${"abcdefghij"[n++ % 10]}${Math.floor(n / 10)}qzx`;
    for (const item of [...tasks, ...habits]) {
      if (item.id !== id) continue;
      item.title = hidden;
      for (const x of sessions) if (x.refId === id) x.title = hidden;
    }
    for (const x of sessions) if (x.id === id) x.title = hidden;
  }
  return { ...c, tasks, habits, plan };
}

/**
 * @param history earlier messages of the conversation ([{ role, text }]), not including this one:
 *                it lets "move it to 7" or "I finished that" refer to what was just mentioned.
 */
function interpret(text, context, history = []) {
  const { clean, ids, excluded } = takeIds(text);
  const past = Array.isArray(history) ? history : [];
  const hidden = [...excluded, ...sameNameAs(context, ids)];
  const result = understand(clean, hidden.length ? isolate(context, hidden) : context, past, 0);
  // a second question in the same sentence ("swap ... with ...") keeps what was already chosen
  if ((ids.length || excluded.length) && result.clarify && result.clarify.options) {
    const carried = [...ids.map(idToken), ...excluded.map(noToken)].join(" ");
    result.clarify.options = result.clarify.options.map((o) => ({ ...o, text: `${o.text} ${carried}` }));
  }
  return result;
}

function understand(text, context, past, depth) {
  const raw = fixTypos(withoutFillers(expandSlang(plainText(String(text || ""))).trim().slice(0, 500)));
  const l = raw.toLowerCase().replace(/[!?.]+$/g, "").replace(/\s+/g, " ").trim();
  if (!l) return say("Type a message, for example “add task maths homework 45 min due friday”, or just tell me how your day is going.");
  const ctx = normalize(context);

  if (depth === 0) {
    const settled = resolveChoice(raw, l, past, context);
    if (settled) return settled;
    const completed = followUp(raw, l, past);
    if (completed) return understand(completed, context, past.slice(0, -2), 1);
    const resolved = resolvePronouns(raw, l, ctx, past);
    if (resolved) return understand(resolved, context, past, 1);
  }

  for (const intent of INTENTS) {
    const result = intent(raw, l, ctx, past);
    if (result) return result;
  }
  return say(`${pick(FALLBACKS, l)}\n• ` + EXAMPLES.slice(0, 6).join("\n• ") + "\nOr just tell me how you're feeling about your workload.");
}

module.exports = { interpret, extractSlots, cleanTitle, findBest, pickSubject };
