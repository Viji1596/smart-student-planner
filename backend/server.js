const express = require("express");
const cors = require("cors");

const userRoutes = require("./routes/userRoutes");
const taskRoutes = require("./routes/taskRoutes");
const scheduleRoutes = require("./routes/scheduleRoutes");
const timetableRoutes = require("./routes/timetableRoutes");

require("dotenv").config();

const app = express();

app.use(cors());
app.use(express.json());
app.use("/api/timetable", timetableRoutes);

app.get("/", (req, res) => {
  res.json({
    message: "Smart Student Planner API is running",
  });
});

const PORT = process.env.PORT || 5000;
app.use("/api/users", userRoutes);
app.use("/api/tasks", taskRoutes);
app.use("/api/schedule", scheduleRoutes);
app.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
});