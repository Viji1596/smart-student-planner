const express = require("express");
const cors = require("cors");
const helmet = require("helmet");
const rateLimit = require("express-rate-limit");

const taskRoutes = require("./routes/taskRoutes");
const scheduleRoutes = require("./routes/scheduleRoutes");
const timetableRoutes = require("./routes/timetableRoutes");
const authRoutes = require("./routes/authRoutes");
const settingsRoutes = require("./routes/settingsRoutes");
const { buildRouter: buildTimetableImportRouter } = require("./routes/timetableImportRoutes");
const habitRoutes = require("./routes/habitRoutes");
const habitCompletionRoutes = require("./routes/habitCompletionRoutes");

const app = express();

// On a host like Render the app sits behind one proxy. Without this, every request looks like it
// comes from the proxy and the rate limiter would count all users together.
app.set("trust proxy", 1);

app.use(helmet());

// Native apps do not use CORS. Only allow origins you explicitly list.
const allowedOrigins = (process.env.CORS_ORIGINS || "")
  .split(",")
  .map((o) => o.trim())
  .filter(Boolean);
app.use(
  cors({
    origin: (origin, callback) =>
      callback(null, !origin || allowedOrigins.includes(origin)),
  })
);

// Uploading a timetable file is the one request that carries a large body.
app.use("/api/timetable/import", express.json({ limit: "14mb" }), buildTimetableImportRouter());

app.use(express.json({ limit: "100kb" }));

app.get("/", (req, res) => res.json({ message: "Smart Student Planner API" }));
app.get("/health", (req, res) =>
  res.json({ ok: true, version: "5e", features: ["timetable-import", "pdf-text", "text-boxes"] })
);

app.use(
  "/api/auth",
  rateLimit({ windowMs: 15 * 60 * 1000, limit: 60, standardHeaders: true }),
  authRoutes
);
app.use("/api/tasks", taskRoutes);
app.use("/api/schedule", scheduleRoutes);
app.use("/api/timetable", timetableRoutes);
app.use("/api/settings", settingsRoutes);
app.use("/api/habits", habitRoutes);
app.use("/api/habit-completions", habitCompletionRoutes);

app.use((req, res) => res.status(404).json({ message: "Not found" }));

// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  if (err && err.type === "entity.too.large") return res.status(413).json({ message: "The file is too large (8 MB at most)." });
  if (err && err.type === "entity.parse.failed") return res.status(400).json({ message: "Invalid request." });
  console.error("Unhandled error:", err);
  res.status(500).json({ message: "Something went wrong." });
});

module.exports = app;
