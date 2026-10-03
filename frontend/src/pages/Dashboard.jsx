import TaskSummary from "../components/TaskSummary";
import ScheduleSummary from "../components/ScheduleSummary";
import TimetableSummary from "../components/TimetableSummary";

function Dashboard() {
  return (
    <div className="dashboard">
      <h1>Task Planner</h1>

      <p className="dashboard-subtitle">
        Organize your tasks, schedule, and study time.
      </p>

      <div className="dashboard-grid">
        <TaskSummary />
        <ScheduleSummary />
        <TimetableSummary />
      </div>

    </div>
  );
}

export default Dashboard;