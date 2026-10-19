const express = require("express");
const { z } = require("zod");
const requireAuth = require("../middleware/authMiddleware");
const prisma = require("../lib/prisma");

const router = express.Router();
router.use(requireAuth);

const idParam = z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/);

const habitBody = z
  .object({
    title: z.string().trim().min(1).max(100),
    minutes: z.coerce.number().int().min(5).max(480),
    priority: z.enum(["Low", "Medium", "High"]),
    kind: z.enum(["DAILY", "DAYS", "WEEKLY"]),
    // ISO weekdays 1..7, comma separated, no duplicates ("1,3,5"); empty unless kind = DAYS
    days: z.string().max(13).regex(/^([1-7](,[1-7]){0,6})?$/).default(""),
    timesPerWeek: z.coerce.number().int().min(0).max(7).default(0),
  })
  .superRefine((h, ctx) => {
    if (h.kind === "DAYS") {
      const list = h.days.split(",").filter(Boolean);
      if (list.length === 0 || new Set(list).size !== list.length) {
        ctx.addIssue({ code: "custom", path: ["days"], message: "Pick at least one weekday, no repeats" });
      }
    }
    if (h.kind === "WEEKLY" && (h.timesPerWeek < 1 || h.timesPerWeek > 7)) {
      ctx.addIssue({ code: "custom", path: ["timesPerWeek"], message: "Use 1 to 7 times per week" });
    }
  });

function toDto(h) {
  return {
    id: h.id,
    title: h.title,
    minutes: h.minutes,
    priority: h.priority,
    kind: h.kind,
    days: h.days,
    timesPerWeek: h.timesPerWeek,
    deleted: h.deletedAt !== null,
    updatedAt: h.updatedAt.toISOString(),
  };
}

function parseSince(req, res) {
  if (!req.query.since) return { ok: true, since: null };
  const since = new Date(String(req.query.since));
  if (isNaN(since.getTime())) {
    res.status(400).json({ message: "Invalid since value" });
    return { ok: false };
  }
  return { ok: true, since };
}

router.get("/", async (req, res) => {
  try {
    const serverTime = new Date();
    const parsed = parseSince(req, res);
    if (!parsed.ok) return;

    const habits = await prisma.habit.findMany({
      where: {
        userId: req.user.id,
        ...(parsed.since ? { updatedAt: { gte: parsed.since } } : { deletedAt: null }),
      },
      orderBy: { updatedAt: "asc" },
    });
    res.json({ habits: habits.map(toDto), serverTime: serverTime.toISOString() });
  } catch (error) {
    console.error("Fetch habits failed:", error);
    res.status(500).json({ message: "Failed to fetch habits" });
  }
});

router.put("/:id", async (req, res) => {
  const id = idParam.safeParse(req.params.id);
  const body = habitBody.safeParse(req.body);
  if (!id.success || !body.success) {
    return res.status(400).json({
      message: "Invalid habit",
      issues: body.success ? undefined : body.error.issues,
    });
  }

  try {
    const existing = await prisma.habit.findUnique({ where: { id: id.data } });
    if (existing && existing.userId !== req.user.id) {
      return res.status(404).json({ message: "Habit not found" });
    }

    // Keep stored data tidy: days only for DAYS, count only for WEEKLY.
    const data = {
      ...body.data,
      days: body.data.kind === "DAYS" ? body.data.days : "",
      timesPerWeek: body.data.kind === "WEEKLY" ? body.data.timesPerWeek : 0,
      deletedAt: null,
    };
    const habit = await prisma.habit.upsert({
      where: { id: id.data },
      create: { id: id.data, userId: req.user.id, ...data },
      update: data,
    });
    res.json(toDto(habit));
  } catch (error) {
    console.error("Upsert habit failed:", error);
    res.status(500).json({ message: "Failed to save habit" });
  }
});

router.delete("/:id", async (req, res) => {
  try {
    const result = await prisma.habit.updateMany({
      where: { id: req.params.id, userId: req.user.id },
      data: { deletedAt: new Date() },
    });
    res.json({ deleted: result.count });
  } catch (error) {
    console.error("Delete habit failed:", error);
    res.status(500).json({ message: "Failed to delete habit" });
  }
});

module.exports = router;
