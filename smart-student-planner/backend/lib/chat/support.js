// The caring and chatty side of the free assistant: it notices when a student is overwhelmed, low or tired,
// answers like a supportive friend (not a therapist), makes the load smaller, and knows when to point to a person.
// With an AI key the AI does this in its own words; this is what works with no key.
const { say, pick, fmtMinutes, openTasks, plannedSessions, sessionMinutes } = require("./common");

// ---------------------------------------------------------------- what the student is feeling
const CRISIS =
  /\b(kill(ing)? myself|end(ing)? my (own )?life|want(ed)? to die|wanna die|suicid\w*|self[- ]?harm\w*|hurt(ing)? myself|no reason to (live|go on)|don'?t want to (live|be here|exist|wake up)|better off (dead|without me)|end it all|can'?t go on)\b/;
const EXAM = /\b(exam|exams|test|tests|viva|finals?|semester|internals?)\b.*\b(scared|nervous|worried|stress\w*|afraid|panic\w*|anxious|dread)\b|\b(scared|nervous|worried|afraid|anxious|dread\w*)\b.*\b(exam|exams|test|tests|viva|finals?|internals?)\b/;
const OVERWHELMED =
  /\b(overwhelm\w*|swamped|drowning|buried (in|under)|too much (to do|work|on my plate|going on|pressure)|can'?t (cope|handle (it|this|all))|falling behind|so behind|way behind|behind on everything|don'?t know where to start|burn(ed|t)?[- ]?out|stress(ed|ing)?( out)?|anxious|anxiety|panic\w*|freaking out|losing it|too many (things|tasks|deadlines))\b/;
const LOW =
  /\b(demotivated|unmotivated|no motivation|can'?t (be bothered|focus|concentrate)|procrastinat\w*|feel(ing)? (low|down|sad|hopeless|useless|stupid|worthless|like a failure)|fed up|sick of (this|studying|everything)|hate (studying|this|my life)|give up|giving up|lazy)\b/;
const TIRED = /\b(i'?m|i am|feeling|so|really|very)\s+(so |really |very |super )?(tired|sleepy|exhausted|drained|burnt)\b|\bno energy\b/;
const WIN = /\b(i did it|i finished everything|best day|so productive|feeling (great|good|proud|motivated|better)|crushed it|nailed it|got (an? )?(a|distinction|full marks)|feel(ing)? better now)\b/;
const TALK = /\b(i just want to (talk|vent)|can we talk|talk to me|need (to talk|someone to talk)|just listen|let me vent)\b/;
const BREAK = /\b(i need (a )?(break|rest)|take a break|need to rest|break time)\b/;

/** What the student seems to be feeling, or null. Looks for the most serious first. */
function detect(l) {
  if (CRISIS.test(l)) return "crisis";
  if (EXAM.test(l)) return "exam";
  if (OVERWHELMED.test(l)) return "overwhelmed";
  if (LOW.test(l)) return "low";
  if (TIRED.test(l)) return "tired";
  if (TALK.test(l)) return "talk";
  if (BREAK.test(l)) return "break";
  if (WIN.test(l)) return "win";
  return null;
}

// ------------------------------------------------------------------------------- the answers
const CRISIS_REPLY = [
  "I'm really sorry you're feeling this much pain, and I'm glad you told me. You deserve support from a real person right now.",
  "",
  "• If you might act on these thoughts, or you're in danger, please call 112 (India's emergency number), go to the nearest hospital, or ask someone near you to stay with you.",
  "• You can talk to a trained counsellor for free, any time of day or night, in your own language: Tele-MANAS on 14416 (or 1-800-891-4416). It exists for exactly this.",
  "• If you're outside India, please contact your local emergency number or a local crisis line.",
  "",
  "If there's a friend, family member or someone at college you trust, please tell them how you feel today. You don't have to carry this alone. Your plan and your tasks can wait: nothing here matters more than you being safe. I'm here to talk too, but I'm an app, and a person can help in ways I can't.",
].join("\n");

function load(ctx) {
  const open = openTasks(ctx);
  const overdue = open.filter((t) => t.due && t.due < ctx.today).length;
  const planned = plannedSessions(ctx).reduce((sum, s) => sum + sessionMinutes(s), 0);
  const parts = [];
  if (open.length) parts.push(`${open.length} open task${open.length === 1 ? "" : "s"}${overdue ? ` (${overdue} overdue)` : ""}`);
  if (planned) parts.push(`about ${fmtMinutes(planned)} planned for the rest of today`);
  return parts.length ? parts.join(", with ") : null;
}

const chips = (...pairs) => pairs.map(([label, text]) => ({ label, text }));

function overwhelmed(ctx, seed) {
  const openers = [
    "That sounds like a lot, and I'm sorry you're carrying it. Feeling overwhelmed doesn't mean you're failing: it usually means there's too much in your head at once.",
    "I hear you. When everything piles up it all feels equally urgent, and it isn't. Let's make it smaller.",
    "Thanks for telling me. That feeling is really common, especially when deadlines bunch up. You don't have to do it all at once.",
  ];
  const l = load(ctx);
  const body = [
    pick(openers, seed),
    l ? `Here's what I can see: ${l}.` : null,
    "Let's shrink it: here is where I'd start. Just the first one, nothing else.",
  ].filter(Boolean).join("\n\n");
  return say(
    body,
    [{ type: "show_focus", args: {} }],
    { question: "What would help most?", options: chips(["Lighten today's plan", "lighten my day"], ["I need a break", "I need a break"], ["Just talk", "I just want to talk"]) }
  );
}

function low(ctx, seed) {
  const openers = [
    "That's a hard place to be, and it happens to everyone, even the most organised people. Motivation usually follows starting, not the other way round.",
    "I'm sorry it feels heavy right now. Being stuck doesn't say anything bad about you; it's often just too much, or too unclear where to begin.",
  ];
  return say(
    `${pick(openers, seed)}\n\nTry the 10-minute trick: pick the one smallest task below, set a timer for 10 minutes, and give yourself full permission to stop after. Most of the time you won't want to.`,
    [{ type: "show_focus", args: {} }],
    { question: "Want me to take something off your plate?", options: chips(["Lighten today's plan", "lighten my day"], ["I just want to talk", "I just want to talk"]) }
  );
}

function tired(ctx, seed) {
  const openers = [
    "You sound worn out. Rest isn't a reward you earn at the end; it's part of studying well.",
    "Sounds like your battery is low. A short real break often does more than pushing through.",
  ];
  return say(
    `${pick(openers, seed)}\n\nIf you can, take 15–20 minutes away from the screen: water, a little movement, fresh air. If you're running on too little sleep, a lighter day beats a heroic one.`,
    [],
    { question: "Want me to ease today's plan?", options: chips(["Lighten today's plan", "lighten my day"], ["What's essential?", "what should I focus on"]) }
  );
}

function exam(ctx, seed) {
  const openers = [
    "Exam nerves are really common, and it means you care. The trick is to turn the big scary thing into a short list.",
    "That's a normal worry before exams. Fear mostly shrinks when the work gets broken into small, clear pieces.",
  ];
  return say(
    `${pick(openers, seed)}\n\nHere's what is most pressing:`,
    [{ type: "show_focus", args: {} }],
    { question: "What next?", options: chips(["How am I doing?", "how am I doing"], ["Lighten today's plan", "lighten my day"], ["I just want to talk", "I just want to talk"]) }
  );
}

function win(ctx, seed) {
  const lines = [
    "That's great to hear! Enjoy it for a moment: you earned it.",
    "Love that. Noticing the good days matters as much as fixing the bad ones.",
  ];
  return say(pick(lines, seed), [], { question: "Want to see how your week is going?", options: chips(["Show my report", "how am I doing"]) });
}

function talk(ctx, seed) {
  return say(
    "Of course. I'm listening, and there's no plan or deadline in this part of the chat. What's on your mind?\n\nIf it feels heavy, talking to a friend, someone at home or a counsellor helps too. The free Tele-MANAS line (14416) is there any time.",
  );
}

function takeBreak(ctx, seed) {
  return say(
    "Yes, take it. Stand up, drink some water, look at something far away for a minute, and give yourself 15 minutes without your phone if you can.\n\nWhen you're back, I'll be here, and nothing will have fallen apart. Want me to ease the rest of today?",
    [],
    { question: "Ease today?", options: chips(["Lighten today's plan", "lighten my day"], ["No, I'm okay", "thanks"]) }
  );
}

/** A caring answer if the message shows how the student feels, otherwise null. */
function supportIntent(raw, l, ctx) {
  const feeling = detect(l);
  if (!feeling) return null;
  if (feeling === "crisis") return say(CRISIS_REPLY);
  const seed = l;
  return { overwhelmed, low, tired, exam, win, talk, break: takeBreak }[feeling](ctx, seed);
}

// --------------------------------------------------------------------------------- small talk
const JOKES = [
  "Why did the student eat their homework? The teacher said it was a piece of cake.",
  "I told my study plan a joke. It didn't get it, so I rescheduled it for tomorrow.",
  "Procrastination is like a credit card: it's a lot of fun until you get the bill.",
];

const MOTIVATION = [
  "You don't have to feel ready, you just have to start small. Ten minutes counts.",
  "Progress beats perfection. One finished session today is worth more than a perfect plan you never use.",
  "Future you will be glad you did the first ten minutes today.",
];

function greeting(ctx, l) {
  const hour = ctx.nowMin === null ? 12 : Math.floor(ctx.nowMin / 60);
  const part = /good morning|morning/.test(l) ? "morning" : /good afternoon/.test(l) ? "afternoon" : /good evening/.test(l) ? "evening" : hour < 12 ? "morning" : hour < 17 ? "afternoon" : "evening";
  const next = plannedSessions(ctx)[0];
  const hello = pick([`Good ${part}!`, `Hey! Good ${part}.`, `Hi there. Good ${part}!`], l);
  const plan = next
    ? ` Next up is “${next.title}” at ${next.start}.`
    : " You have nothing else planned for today.";
  return say(`${hello}${plan} What would you like to do?`, [], {
    question: "Quick start",
    options: chips(["My plan today", "what's my plan today"], ["What should I focus on?", "what should I focus on"], ["How am I doing?", "how am I doing"]),
  });
}

function smallTalkIntent(raw, l, ctx) {
  if (/^(hi+|hello|hey+|hiya|howdy|yo|good (morning|afternoon|evening)|morning|evening)[\s!.,]*$/.test(l)) return greeting(ctx, l);
  if (/^(how are you|how'?s it going|how are you doing|what'?s up|sup)\b/.test(l)) {
    return say(pick([
      "Doing well, thanks for asking. More importantly: how are you? Is anything on your mind, or shall we look at your plan?",
      "I'm good! I like it when the plan is in order. How are you feeling today?",
    ], l));
  }
  if (/^(who are you|what are you|what'?s your name|are you (a )?(bot|human|real|ai))\b/.test(l)) {
    return say("I'm your planner assistant. I know your tasks, habits, timetable and today's plan, and I can change them for you, or just help you think. I'm not a person and not a therapist, but I'm happy to help you sort things out. Say “help” to see what I can do.");
  }
  if (/^(thanks?|thank you|thx|ty|cheers|ok(ay)?|cool|great|nice|got it|perfect|awesome|good)[\s!.]*$/.test(l)) {
    return say(pick(["You're welcome!", "Anytime.", "Glad I could help. I'm here if you need anything else.", "Happy to help!"], l));
  }
  if (/^(good ?night|night night|bye|goodbye|see you|see ya|talk later|gtg)\b/.test(l)) {
    return say(pick(["Good night! Rest well. I'll keep tomorrow's plan ready.", "See you later! Take care of yourself."], l));
  }
  if (/\b(joke|make me laugh|something funny)\b/.test(l)) return say(pick(JOKES, l));
  if (/\b(motivate me|inspire me|encourage me|pep talk|i need motivation)\b/.test(l)) {
    return say(pick(MOTIVATION, l), [{ type: "show_focus", args: {} }]);
  }
  if (/\b(you'?re|you are|u r)\s+(great|awesome|amazing|the best|helpful|good|smart|brilliant)\b|\bgood bot\b|\blove you\b/.test(l)) {
    return say(pick(["That's kind of you, thank you!", "Aw, thanks. You're doing the hard part."], l));
  }
  if (/^(i'?m )?bored\b/.test(l)) {
    return say("Bored is a good time to knock out something small. Want me to show a quick win?", [{ type: "show_focus", args: {} }]);
  }
  return null;
}

module.exports = { supportIntent, smallTalkIntent, detect, CRISIS_REPLY };
