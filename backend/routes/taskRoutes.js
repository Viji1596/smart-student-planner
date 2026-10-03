const express = require("express");
const prisma = require("../lib/prisma");

const router = express.Router();

// Create a task
router.post("/", async (req, res) => {
  try {
    const { userId, title, priority, estimatedMinutes, dueDate } = req.body;

    const task = await prisma.task.create({
      data: {
        userId,
        title,
        priority,
        estimatedMinutes: Number(estimatedMinutes),
        dueDate: dueDate ? new Date(dueDate) : null,
      },
    });

    res.status(201).json(task);
  } catch (error) {
    res.status(500).json({
      message: "Failed to create task",
      error: error.message,
    });
  }
});

// Get all tasks
router.get("/", async (req, res) => {
  try {
    const tasks = await prisma.task.findMany({
      orderBy: {
        createdAt: "desc",
      },
    });

    res.json(tasks);
  } catch (error) {
    res.status(500).json({
      message: "Failed to fetch tasks",
      error: error.message,
    });
  }
});

// Update task completion status
router.put("/:id/complete", async (req, res) => {
  try {
    const { completed } = req.body;

    const task = await prisma.task.update({
      where: {
        id: req.params.id,
      },
      data: {
        completed: Boolean(completed),
      },
    });

    res.json(task);
  } catch (error) {
    res.status(500).json({
      message: "Failed to update task",
      error: error.message,
    });
  }
});
// Delete a task
router.delete("/:id", async (req, res) => {
  try {
    await prisma.task.delete({
      where: {
        id: req.params.id,
      },
    });

    res.json({
      message: "Task deleted successfully",
    });
  } catch (error) {
    res.status(500).json({
      message: "Failed to delete task",
      error: error.message,
    });
  }
});
module.exports = router;