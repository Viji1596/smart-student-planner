const express = require("express");
const { z } = require("zod");
const requireAuth = require("../middleware/authMiddleware");
const prisma = require("../lib/prisma");

const router = express.Router();
router.use(requireAuth);

// "HH:mm", 24-hour, zero padded (so plain string comparison orders times correctly)
const hm = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, "Use HH:mm");

const entryBody = z
  .object({
    dayOfWeek: z.coerce.number().int().min(1).max(7), // 1 = Monday ... 7 = Sunday
    title: z.string().trim().min(1).max(100),
    startTime: hm,
    endTime: hm,
  })
  .refine((b) => b.startTime < b.endTime, {
    message: "endTime must be after startTime",
    path: ["endTime"],
  });

const idParam = z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/);

function toDto(entry) {
  return {
    id: entry.id,
    dayOfWeek: entry.dayOfWeek,
    title: entry.title,
    startTime: entry.startTime,
    endTime: entry.endTime,
    deleted: entry.deletedAt !== null,
    updatedAt: entry.updatedAt.toISOString(),
  };
}

// Same pull contract as tasks: no ?since => live entries; ?since => every change incl. deletions.
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

    const entries = await prisma.timetableEntry.findMany({
      where: {
        userId: req.user.id,
        ...(since ? { updatedAt: { gte: since } } : { deletedAt: null }),
      },
      orderBy: { updatedAt: "asc" },
    });

    res.json({ entries: entries.map(toDto), serverTime: serverTime.toISOString() });
  } catch (error) {
    console.error("Fetch timetable failed:", error);
    res.status(500).json({ message: "Failed to fetch timetable" });
  }
});

router.put("/:id", async (req, res) => {
  const id = idParam.safeParse(req.params.id);
  const body = entryBody.safeParse(req.body);

  if (!id.success || !body.success) {
    return res.status(400).json({
      message: "Invalid timetable entry",
      issues: body.success ? undefined : body.error.issues,
    });
  }

  try {
    const existing = await prisma.timetableEntry.findUnique({ where: { id: id.data } });
    if (existing && existing.userId !== req.user.id) {
      return res.status(404).json({ message: "Entry not found" });
    }

    const data = { ...body.data, deletedAt: null };
    const entry = await prisma.timetableEntry.upsert({
      where: { id: id.data },
      create: { id: id.data, userId: req.user.id, ...data },
      update: data,
    });

    res.json(toDto(entry));
  } catch (error) {
    console.error("Upsert timetable entry failed:", error);
    res.status(500).json({ message: "Failed to save timetable entry" });
  }
});

router.delete("/:id", async (req, res) => {
  try {
    const result = await prisma.timetableEntry.updateMany({
      where: { id: req.params.id, userId: req.user.id },
      data: { deletedAt: new Date() },
    });
    res.json({ deleted: result.count });
  } catch (error) {
    console.error("Delete timetable entry failed:", error);
    res.status(500).json({ message: "Failed to delete timetable entry" });
  }
});

module.exports = router;
