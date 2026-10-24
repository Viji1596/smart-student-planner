const express = require("express");
const { z } = require("zod");
const requireAuth = require("../middleware/authMiddleware");
const prisma = require("../lib/prisma");

const router = express.Router();
router.use(requireAuth);

const idParam = z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/);

function isRealDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const d = new Date(`${value}T00:00:00Z`);
  return !isNaN(d.getTime()) && d.toISOString().slice(0, 10) === value;
}

const completionBody = z.object({
  habitId: z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/),
  date: z.string().refine(isRealDate, "Use a real date as YYYY-MM-DD"),
});

function toDto(c) {
  return {
    id: c.id,
    habitId: c.habitId,
    date: c.date,
    deleted: c.deletedAt !== null,
    updatedAt: c.updatedAt.toISOString(),
  };
}

router.get("/", async (req, res) => {
  try {
    const serverTime = new Date();
    let since = null;
    if (req.query.since) {
      since = new Date(String(req.query.since));
      if (isNaN(since.getTime())) {
        return res.status(400).json({ message: "Invalid since value" });
      }
    }

    const completions = await prisma.habitCompletion.findMany({
      where: {
        userId: req.user.id,
        ...(since ? { updatedAt: { gte: since } } : { deletedAt: null }),
      },
      orderBy: { updatedAt: "asc" },
    });
    res.json({ completions: completions.map(toDto), serverTime: serverTime.toISOString() });
  } catch (error) {
    console.error("Fetch habit completions failed:", error);
    res.status(500).json({ message: "Failed to fetch completions" });
  }
});

// The id must be `${habitId}_${date}`, which makes "done today" idempotent across devices.
router.put("/:id", async (req, res) => {
  const id = idParam.safeParse(req.params.id);
  const body = completionBody.safeParse(req.body);
  if (!id.success || !body.success) {
    return res.status(400).json({
      message: "Invalid completion",
      issues: body.success ? undefined : body.error.issues,
    });
  }
  if (id.data !== `${body.data.habitId}_${body.data.date}`) {
    return res.status(400).json({ message: "id must be habitId_date" });
  }

  try {
    const habit = await prisma.habit.findUnique({ where: { id: body.data.habitId } });
    if (!habit || habit.userId !== req.user.id) {
      return res.status(404).json({ message: "Habit not found" });
    }

    const completion = await prisma.habitCompletion.upsert({
      where: { id: id.data },
      create: { id: id.data, userId: req.user.id, habitId: body.data.habitId, date: body.data.date },
      update: { deletedAt: null },
    });
    res.json(toDto(completion));
  } catch (error) {
    console.error("Upsert habit completion failed:", error);
    res.status(500).json({ message: "Failed to save completion" });
  }
});

// Un-doing "done" is a soft delete so other devices hear about it.
router.delete("/:id", async (req, res) => {
  try {
    const result = await prisma.habitCompletion.updateMany({
      where: { id: req.params.id, userId: req.user.id },
      data: { deletedAt: new Date() },
    });
    res.json({ deleted: result.count });
  } catch (error) {
    console.error("Delete habit completion failed:", error);
    res.status(500).json({ message: "Failed to delete completion" });
  }
});

module.exports = router;
