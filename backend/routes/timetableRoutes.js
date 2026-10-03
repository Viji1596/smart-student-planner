const express = require("express");
const prisma = require("../lib/prisma");

const router = express.Router();

// Get all timetable entries
router.get("/", async (req, res) => {
  try {
    const entries = await prisma.timetableEntry.findMany({
      orderBy: [
        {
          dayOfWeek: "asc",
        },
        {
          startTime: "asc",
        },
      ],
    });

    res.json(entries);
  } catch (error) {
    res.status(500).json({
      message: "Failed to fetch timetable entries",
      error: error.message,
    });
  }
});

module.exports = router;