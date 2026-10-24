// Everything the assistant can ask the app to do. One list, used for three things:
//  - the rule-based interpreter produces these,
//  - the AI model is offered them as tools,
//  - every action (from either) is validated here before it reaches the app, and the app checks again.
// Deleting always needs the user's confirmation, whatever the AI says.
const { z } = require("zod");

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const hm = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/);
const priority = z.enum(["Low", "Medium", "High"]);
const id = z.string().min(1).max(80);
const minutes = z.number().int().min(1).max(1440);
const day = z.enum(["today", "tomorrow"]);

const ACTIONS = {
  add_task: {
    confirm: false,
    description: "Add a new task. Use when the student wants something added to their to-do list.",
    schema: z.object({ title: z.string().trim().min(1).max(200), minutes: minutes.optional(), priority: priority.optional(), due: isoDate.optional() }),
    input_schema: {
      type: "object",
      properties: {
        title: { type: "string" },
        minutes: { type: "integer", description: "How long it takes, in minutes (default 30)" },
        priority: { type: "string", enum: ["Low", "Medium", "High"] },
        due: { type: "string", description: "Due date as YYYY-MM-DD" },
      },
      required: ["title"],
    },
  },
  update_task: {
    confirm: false,
    description: "Change an existing task: its name, length, priority or due date. Use the task id from the data.",
    schema: z
      .object({ taskId: id, title: z.string().trim().min(1).max(200).optional(), minutes: minutes.optional(), priority: priority.optional(), due: z.union([isoDate, z.literal("none")]).optional() })
      .refine((a) => a.title || a.minutes || a.priority || a.due, "nothing to change"),
    input_schema: {
      type: "object",
      properties: {
        taskId: { type: "string" },
        title: { type: "string" },
        minutes: { type: "integer" },
        priority: { type: "string", enum: ["Low", "Medium", "High"] },
        due: { type: "string", description: 'Due date as YYYY-MM-DD, or "none" to remove the due date' },
      },
      required: ["taskId"],
    },
  },
  complete_task: {
    confirm: false,
    description: "Mark a task as completely done.",
    schema: z.object({ taskId: id }),
    input_schema: { type: "object", properties: { taskId: { type: "string" } }, required: ["taskId"] },
  },
  log_progress: {
    confirm: false,
    description: "Record that the student worked on a task for some minutes (not necessarily finished).",
    schema: z.object({ taskId: id, minutes }),
    input_schema: { type: "object", properties: { taskId: { type: "string" }, minutes: { type: "integer" } }, required: ["taskId", "minutes"] },
  },
  delete_task: {
    confirm: true,
    description: "Delete a task. The app will ask the student to confirm.",
    schema: z.object({ taskId: id }),
    input_schema: { type: "object", properties: { taskId: { type: "string" } }, required: ["taskId"] },
  },
  add_habit: {
    confirm: false,
    description: "Add a recurring habit: every day (DAILY), on fixed weekdays (DAYS, days 1=Monday..7=Sunday) or N times a week (WEEKLY).",
    schema: z
      .object({
        title: z.string().trim().min(1).max(100),
        minutes: z.number().int().min(5).max(480).optional(),
        priority: priority.optional(),
        kind: z.enum(["DAILY", "DAYS", "WEEKLY"]),
        days: z.array(z.number().int().min(1).max(7)).min(1).max(7).optional(),
        perWeek: z.number().int().min(1).max(7).optional(),
      })
      .refine((a) => a.kind !== "DAYS" || (a.days && new Set(a.days).size === a.days.length), "days needed")
      .refine((a) => a.kind !== "WEEKLY" || a.perWeek, "perWeek needed"),
    input_schema: {
      type: "object",
      properties: {
        title: { type: "string" },
        minutes: { type: "integer" },
        priority: { type: "string", enum: ["Low", "Medium", "High"] },
        kind: { type: "string", enum: ["DAILY", "DAYS", "WEEKLY"] },
        days: { type: "array", items: { type: "integer" } },
        perWeek: { type: "integer" },
      },
      required: ["title", "kind"],
    },
  },
  complete_habit: {
    confirm: false,
    description: "Mark a habit as done today.",
    schema: z.object({ habitId: id }),
    input_schema: { type: "object", properties: { habitId: { type: "string" } }, required: ["habitId"] },
  },
  delete_habit: {
    confirm: true,
    description: "Delete a habit. The app will ask the student to confirm.",
    schema: z.object({ habitId: id }),
    input_schema: { type: "object", properties: { habitId: { type: "string" } }, required: ["habitId"] },
  },
  move_session: {
    confirm: false,
    description:
      "Move one of today's planned study sessions to a different time today. Give the session id (or the task id) and the time as HH:mm. Set flexible=true to mean 'around then' (the app finds the nearest free slot).",
    schema: z
      .object({ sessionId: id.optional(), taskId: id.optional(), time: hm, flexible: z.boolean().optional() })
      .refine((a) => a.sessionId || a.taskId, "which session?"),
    input_schema: {
      type: "object",
      properties: { sessionId: { type: "string" }, taskId: { type: "string" }, time: { type: "string", description: "HH:mm, 24-hour" }, flexible: { type: "boolean" } },
      required: ["time"],
    },
  },
  place_task: {
    confirm: false,
    description:
      "Schedule a task at a time today when it has no planned session yet (for example 'do it at 9pm' about a task that was just added). It creates a session there and keeps it. If the task already has a session today, use move_session instead.",
    schema: z.object({ taskId: id, time: hm, flexible: z.boolean().optional() }),
    input_schema: {
      type: "object",
      properties: { taskId: { type: "string" }, time: { type: "string", description: "HH:mm, 24-hour" }, flexible: { type: "boolean" } },
      required: ["taskId", "time"],
    },
  },
  swap_sessions: {
    confirm: false,
    description: "Swap the times of two of today's planned sessions: each takes the other's start time. Use the two session ids.",
    schema: z.object({ sessionA: id, sessionB: id }).refine((a) => a.sessionA !== a.sessionB, "two different sessions"),
    input_schema: {
      type: "object",
      properties: { sessionA: { type: "string" }, sessionB: { type: "string" } },
      required: ["sessionA", "sessionB"],
    },
  },
  defer_task: {
    confirm: false,
    description:
      "Put a task off: it will not be planned before the given day (use for 'do it tomorrow', 'postpone it to friday'). This does NOT change the due date; use update_task for that.",
    schema: z.object({ taskId: id, until: isoDate }),
    input_schema: {
      type: "object",
      properties: { taskId: { type: "string" }, until: { type: "string", description: "First day it may be planned, YYYY-MM-DD" } },
      required: ["taskId", "until"],
    },
  },
  start_task: {
    confirm: false,
    description: "Bring a put-off task back: it can be planned from today again.",
    schema: z.object({ taskId: id }),
    input_schema: { type: "object", properties: { taskId: { type: "string" } }, required: ["taskId"] },
  },
  lighten_day: {
    confirm: true,
    description:
      "Make today lighter: keep the most urgent tasks (about keepMinutes of work, default 120) and put the rest off until tomorrow. Use when the student says they are overwhelmed or have too much to do. The app shows exactly what would move and asks the student to confirm.",
    schema: z.object({ keepMinutes: z.number().int().min(30).max(600).optional() }),
    input_schema: { type: "object", properties: { keepMinutes: { type: "integer", description: "Roughly how much work to keep today (minutes)" } } },
  },
  show_report: {
    confirm: false,
    description: "Show the student's performance report (work done, sessions missed, tasks, habits and streaks) for the last 7, 14 or 30 days.",
    schema: z.object({ days: z.number().int().min(1).max(60).optional() }),
    input_schema: { type: "object", properties: { days: { type: "integer", description: "7, 14 or 30" } } },
  },
  show_focus: {
    confirm: false,
    description: "Show the three most important things to start with, most urgent first, with a tiny first step.",
    schema: z.object({}),
    input_schema: { type: "object", properties: {} },
  },
  skip_session: {
    confirm: false,
    description: "Skip one of today's planned sessions: its work is planned again for later today.",
    schema: z.object({ sessionId: id.optional(), taskId: id.optional() }).refine((a) => a.sessionId || a.taskId, "which session?"),
    input_schema: { type: "object", properties: { sessionId: { type: "string" }, taskId: { type: "string" } } },
  },
  replan: {
    confirm: false,
    description: "Re-plan the rest of today (sessions the student moved by hand stay where they are).",
    schema: z.object({}),
    input_schema: { type: "object", properties: {} },
  },
  show_plan: {
    confirm: false,
    description: "Show the plan for today or tomorrow in the chat.",
    schema: z.object({ day }),
    input_schema: { type: "object", properties: { day: { type: "string", enum: ["today", "tomorrow"] } }, required: ["day"] },
  },
  show_free_time: {
    confirm: false,
    description: "Show how much free time is left today or tomorrow.",
    schema: z.object({ day }),
    input_schema: { type: "object", properties: { day: { type: "string", enum: ["today", "tomorrow"] } }, required: ["day"] },
  },
  show_tasks: {
    confirm: false,
    description: "List tasks: open, overdue, or due soon (within a week).",
    schema: z.object({ filter: z.enum(["open", "overdue", "due_soon"]) }),
    input_schema: { type: "object", properties: { filter: { type: "string", enum: ["open", "overdue", "due_soon"] } }, required: ["filter"] },
  },
  show_missed: {
    confirm: false,
    description: "Show what was missed today.",
    schema: z.object({}),
    input_schema: { type: "object", properties: {} },
  },
};

const ACTION_NAMES = Object.keys(ACTIONS);

/** Returns { action: { type, args, confirm } } or { error }. */
function validateAction(raw) {
  const spec = raw && typeof raw.type === "string" ? ACTIONS[raw.type] : undefined;
  if (!spec) return { error: `Unknown action: ${raw && raw.type}` };
  const parsed = spec.schema.safeParse(raw.args ?? {});
  if (!parsed.success) return { error: `Invalid ${raw.type}: ${parsed.error.issues[0].message}` };
  return { action: { type: raw.type, args: parsed.data, confirm: spec.confirm } };
}

/** The same list in the shape the AI API wants. */
function toolDefinitions() {
  return ACTION_NAMES.map((name) => ({ name, description: ACTIONS[name].description, input_schema: ACTIONS[name].input_schema }));
}

module.exports = { ACTIONS, ACTION_NAMES, validateAction, toolDefinitions };
