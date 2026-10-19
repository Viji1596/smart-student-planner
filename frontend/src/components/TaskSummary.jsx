import { useEffect, useState } from "react";
import { API_URL } from "../services/api";

function TaskSummary() {
  const [taskCount, setTaskCount] = useState(0);

  useEffect(() => {
    const fetchTaskCount = async () => {
      try {
        const response = await fetch(`${API_URL}/tasks`, {
          credentials: "include",
        });
        const tasks = await response.json();

        if (response.ok && Array.isArray(tasks)) {
          const activeTasks = tasks.filter((task) => !task.completed);
          setTaskCount(activeTasks.length);
        }
      } catch (error) {
        console.error("Failed to fetch task count:", error);
      }
    };

    fetchTaskCount();
  }, []);

  return (
    <div className="dashboard-card">
      <h2>Tasks</h2>
      <p>{taskCount} active tasks</p>
    </div>
  );
}

export default TaskSummary;