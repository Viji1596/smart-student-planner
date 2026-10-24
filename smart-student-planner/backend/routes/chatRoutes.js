const express = require("express");
const rateLimit = require("express-rate-limit");
const { z } = require("zod");
const requireAuth = require("../middleware/authMiddleware");
const { interpret } = require("../lib/chat/rules");
const { validateAction } = require("../lib/chat/actions");
const { chatWithAi } = require("../lib/chat/ai");
const { VERSION } = require("../lib/version");

const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const id_ = z.string().min(1).max(80);
const session = z.object({
  id: z.string().max(80),
  title: z.string().max(200),
  kind: z.string().max(10).optional(),
  refId: z.string().max(80).optional(),
  start: z.string().max(5),
  end: z.string().max(5),
  status: z.enum(["PLANNED", "DONE", "MISSED"]),
});
const span = z.object({ label: z.string().max(100), start: z.string().max(5), end: z.string().max(5), kind: z.string().max(12).optional() });
const report = z.object({
  days: z.number(),
  trackedDays: z.number(),
  minutesDone: z.number(),
  minutesDonePrevious: z.number().nullable().optional(),
  sessionsDone: z.number(),
  sessionsMissed: z.number(),
  completionPercent: z.number().nullable().optional(),
  tasksCompleted: z.number(),
  tasksOpen: z.number(),
  overdue: z.number(),
  dueSoon: z.number(),
  onTimePercent: z.number().nullable().optional(),
  bestDay: z.string().max(12).nullable().optional(),
  mostMissed: z.string().max(200).nullable().optional(),
  habits: z
    .array(z.object({ title: z.string().max(200), done: z.number(), expected: z.number(), streak: z.number() }))
    .max(30)
    .default([]),
  // finished within the deadline (30 minutes' grace after the due day), late, missed; scheduled against finished; the grade
  finishedOnTime: z.number().optional(),
  finishedLate: z.number().optional(),
  missedInPeriod: z.number().optional(),
  scheduledTasks: z.number().optional(),
  finishedScheduled: z.number().optional(),
  score: z.number().nullable().optional(),
  grade: z.string().max(3).optional(),
  insights: z.array(z.string().max(400)).max(6).optional(),
});
const day = z.object({
  sessions: z.array(session).max(60).default([]),
  freeMinutes: z.number().optional(),
  busy: z.array(span).max(40).optional(),
  freeBlocks: z.array(span).max(40).optional(),
});

const body = z.object({
  messages: z.array(z.object({ role: z.enum(["user", "assistant"]), text: z.string().max(1000) })).min(1).max(16),
  context: z.object({
    today: date,
    nowMin: z.number().int().min(0).max(1500).optional(),
    tasks: z
      .array(z.object({
        id: z.string().max(80),
        title: z.string().max(200),
        minutes: z.number().optional(),
        spent: z.number().optional(),
        priority: z.string().max(10).optional(),
        due: z.string().max(10).nullable().optional(),
        deferUntil: z.string().max(10).nullable().optional(),
        completed: z.boolean().optional(),
      }))
      .max(200)
      .default([]),
    habits: z
      .array(z.object({ id: z.string().max(80), title: z.string().max(200), minutes: z.number().optional(), kind: z.string().max(10).optional(), doneToday: z.boolean().optional() }))
      .max(60)
      .default([]),
    plan: day.default({ sessions: [] }),
    tomorrow: day.optional(),
    report: report.optional(),
    // the task the student was just talking about, so "it" means that one
    focus: z.object({ taskId: id_, title: z.string().max(200) }).nullable().optional(),
  }),
});

/**
 * POST / : { messages, context } -> { reply, actions, clarify, source }
 * The server only interprets. The app carries out the actions (it holds the planner and the data).
 */
function buildRouter(deps = {}) {
  const ai = deps.chatWithAi || chatWithAi;
  const config = () => ({
    apiKey: deps.apiKey !== undefined ? deps.apiKey : process.env.ANTHROPIC_API_KEY,
    model: deps.model || process.env.CHAT_MODEL,
  });

  const router = express.Router();
  router.use(requireAuth);
  router.use(rateLimit({ windowMs: 60 * 60 * 1000, limit: 150, standardHeaders: true, keyGenerator: (req) => req.user.id }));

  router.get("/status", (req, res) => res.json({ ai: Boolean(config().apiKey) }));

  router.post("/", async (req, res) => {
    const parsed = body.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ message: "That message couldn't be understood." });
    const { messages, context } = parsed.data;
    const last = messages[messages.length - 1];
    if (last.role !== "user") return res.status(400).json({ message: "The last message must be from the student." });

    const { apiKey, model } = config();
    let notice;
    if (apiKey) {
      try {
        const out = await ai({ apiKey, model, history: messages, context });
        return res.json({ ...out, source: "ai", version: VERSION });
      } catch (error) {
        console.error("Chat AI failed, using the built-in assistant:", error.message);
        notice = "The AI is unavailable right now, so I used my basic mode.";
      }
    }

    // the earlier messages let "move it to 9pm" or "I finished that" refer to what was just talked about
    const result = interpret(last.text, context, messages.slice(0, -1));
    const actions = result.actions.map(validateAction).filter((v) => v.action).map((v) => v.action);
    return res.json({ reply: result.reply, actions, clarify: result.clarify, source: "rules", notice, version: VERSION });
  });

  return router;
}

module.exports = { buildRouter };
