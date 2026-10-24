const express = require("express");
const { z } = require("zod");
const requireAuth = require("../middleware/authMiddleware");
const prisma = require("../lib/prisma");

const router = express.Router();
router.use(requireAuth);

const taskBody = z.object({
  title: z.string().trim().min(1).max(200),
  priority: z.enum(["Low", "Medium", "High"]),
  estimatedMinutes: z.coerce.number().int().min(1).max(1440),
  spentMinutes: z.coerce.number().int().min(0).max(1440).optional(),
  dueDate: z.string().datetime({ offset: true }).nullable().optional(),
  completed: z.boolean().optional(),
});

const idParam = z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/);

function toDto(task) {
  return {
    id: task.id,
    title: task.title,
    priority: task.priority,
    estimatedMinutes: task.estimatedMinutes,
    spentMinutes: task.spentMinutes ?? 0,
    dueDate: task.dueDate ? task.dueDate.toISOString() : null,
    completed: task.completed,
    deleted: task.deletedAt !== null,
    updatedAt: task.updatedAt.toISOString(),
  };
}

// Pull changes. Without ?since you get everything that is not deleted.
// With ?since=<ISO> you get every change (including deletions) since then.
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

    const tasks = await prisma.task.findMany({
      where: {
        userId: req.user.id,
        ...(since ? { updatedAt: { gte: since } } : { deletedAt: null }),
      },
      orderBy: { updatedAt: "asc" },
    });

    res.json({ tasks: tasks.map(toDto), serverTime: serverTime.toISOString() });
  } catch (error) {
    console.error("Fetch tasks failed:", error);
    res.status(500).json({ message: "Failed to fetch tasks" });
  }
});

// Create or update. The client chooses the id, so tasks created offline
// can be pushed later. Last write wins.
router.put("/:id", async (req, res) => {
  const id = idParam.safeParse(req.params.id);
  const body = taskBody.safeParse(req.body);

  if (!id.success || !body.success) {
    return res.status(400).json({
      message: "Invalid task",
      issues: body.success ? undefined : body.error.issues,
    });
  }

  try {
    const existing = await prisma.task.findUnique({ where: { id: id.data } });
    if (existing && existing.userId !== req.user.id) {
      return res.status(404).json({ message: "Task not found" });
    }

    const data = {
      title: body.data.title,
      priority: body.data.priority,
      estimatedMinutes: body.data.estimatedMinutes,
      spentMinutes: Math.min(body.data.spentMinutes ?? 0, body.data.estimatedMinutes),
      dueDate: body.data.dueDate ? new Date(body.data.dueDate) : null,
      completed: body.data.completed ?? false,
      deletedAt: null,
    };

    const task = await prisma.task.upsert({
      where: { id: id.data },
      create: { id: id.data, userId: req.user.id, ...data },
      update: data,
    });

    res.json(toDto(task));
  } catch (error) {
    console.error("Upsert task failed:", error);
    res.status(500).json({ message: "Failed to save task" });
  }
});

// Soft delete so that other devices can learn about it.
router.delete("/:id", async (req, res) => {
  try {
    const result = await prisma.task.updateMany({
      where: { id: req.params.id, userId: req.user.id },
      data: { deletedAt: new Date() },
    });
    // Idempotent: deleting something already gone is fine.
    res.json({ deleted: result.count });
  } catch (error) {
    console.error("Delete task failed:", error);
    res.status(500).json({ message: "Failed to delete task" });
  }
});

module.exports = router;
