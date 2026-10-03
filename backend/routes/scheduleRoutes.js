const express = require("express");
const prisma = require("../lib/prisma");

const router = express.Router();

// Get all scheduled sessions
router.get("/", async (req, res) => {
  try {
    const sessions = await prisma.scheduleSession.findMany({
      orderBy: {
        startTime: "asc",
      },
    });

    res.json(sessions);
  } catch (error) {
    res.status(500).json({
      message: "Failed to fetch schedule sessions",
      error: error.message,
    });
  }
});

module.exports = router;