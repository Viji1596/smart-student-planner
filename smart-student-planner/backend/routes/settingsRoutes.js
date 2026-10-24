const express = require("express");
const { z } = require("zod");
const requireAuth = require("../middleware/authMiddleware");
const prisma = require("../lib/prisma");

const router = express.Router();
router.use(requireAuth);

const hm = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, "Use HH:mm");
const mealMinutes = z.coerce.number().int().min(5).max(180);

function validTimezone(tz) {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

const settingsBody = z
  .object({
    wakeTime: hm,
    sleepTime: hm,
    morningRoutineMinutes: z.coerce.number().int().min(0).max(240),
    breakfastStart: hm,
    breakfastMinutes: mealMinutes,
    lunchStart: hm,
    lunchMinutes: mealMinutes,
    dinnerStart: hm,
    dinnerMinutes: mealMinutes,
    timezone: z.string().min(1).max(64).refine(validTimezone, "Unknown timezone"),

    // Older app versions don't send these yet, so every one has a default.
    commuteToMinutes: z.coerce.number().int().min(0).max(240).default(0),
    commuteFromMinutes: z.coerce.number().int().min(0).max(240).default(0),
    arriveEarlyMinutes: z.coerce.number().int().min(0).max(60).default(10),
    restStart: hm.default("16:00"),
    restMinutes: z.coerce.number().int().min(0).max(180).default(0),
    breakMinutes: z.coerce.number().int().min(0).max(60).default(10),
    longBreakAfterMinutes: z.coerce.number().int().min(0).max(480).default(120),
    longBreakMinutes: z.coerce.number().int().min(0).max(120).default(20),
  })
  .refine((s) => s.wakeTime !== s.sleepTime, {
    message: "Wake and sleep time must differ",
    path: ["sleepTime"],
  });

function toDto(s) {
  return {
    wakeTime: s.wakeTime,
    sleepTime: s.sleepTime,
    morningRoutineMinutes: s.morningRoutineMinutes,
    breakfastStart: s.breakfastStart,
    breakfastMinutes: s.breakfastMinutes,
    lunchStart: s.lunchStart,
    lunchMinutes: s.lunchMinutes,
    dinnerStart: s.dinnerStart,
    dinnerMinutes: s.dinnerMinutes,
    timezone: s.timezone,
    commuteToMinutes: s.commuteToMinutes ?? 0,
    commuteFromMinutes: s.commuteFromMinutes ?? 0,
    arriveEarlyMinutes: s.arriveEarlyMinutes ?? 10,
    restStart: s.restStart ?? "16:00",
    restMinutes: s.restMinutes ?? 0,
    breakMinutes: s.breakMinutes ?? 10,
    longBreakAfterMinutes: s.longBreakAfterMinutes ?? 120,
    longBreakMinutes: s.longBreakMinutes ?? 20,
    updatedAt: s.updatedAt.toISOString(),
  };
}

// `settings` is null until the user saves once, so a device never overwrites
// another device's real settings with its own defaults.
router.get("/", async (req, res) => {
  try {
    const serverTime = new Date().toISOString();
    const settings = await prisma.userSettings.findUnique({ where: { userId: req.user.id } });
    res.json({ settings: settings ? toDto(settings) : null, serverTime });
  } catch (error) {
    console.error("Fetch settings failed:", error);
    res.status(500).json({ message: "Failed to fetch settings" });
  }
});

router.put("/", async (req, res) => {
  const body = settingsBody.safeParse(req.body);
  if (!body.success) {
    return res.status(400).json({ message: "Invalid settings", issues: body.error.issues });
  }

  try {
    const settings = await prisma.userSettings.upsert({
      where: { userId: req.user.id },
      create: { userId: req.user.id, ...body.data },
      update: body.data,
    });
    res.json(toDto(settings));
  } catch (error) {
    console.error("Save settings failed:", error);
    res.status(500).json({ message: "Failed to save settings" });
  }
});

module.exports = router;
