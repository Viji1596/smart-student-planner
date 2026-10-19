const express = require("express");
const requireAuth = require("../middleware/authMiddleware");
const prisma = require("../lib/prisma");

const router = express.Router();

// Get all scheduled sessions for the logged-in user
router.get("/", requireAuth, async (req, res) => {
  try {
    const sessions = await prisma.scheduleSession.findMany({
      where: {
        userId: req.user.id,
      },
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