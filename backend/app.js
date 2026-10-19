const express = require("express");
const cors = require("cors");
const helmet = require("helmet");
const rateLimit = require("express-rate-limit");

const taskRoutes = require("./routes/taskRoutes");
const scheduleRoutes = require("./routes/scheduleRoutes");
const timetableRoutes = require("./routes/timetableRoutes");
const authRoutes = require("./routes/authRoutes");
const settingsRoutes = require("./routes/settingsRoutes");
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

app.use(express.json({ limit: "100kb" }));

app.get("/", (req, res) => res.json({ message: "Smart Student Planner API" }));
app.get("/health", (req, res) => res.json({ ok: true }));

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

module.exports = app;
