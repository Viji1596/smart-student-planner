import { useEffect, useState } from "react";
import TaskForm from "../components/TaskForm";
import { API_URL } from "../services/api";

function Tasks() {
  const [tasks, setTasks] = useState([]);

  const fetchTasks = async () => {
    try {
      const response = await fetch(`${API_URL}/tasks`, {
        credentials: "include",
      });
      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || "Failed to fetch tasks");
      }

      setTasks(data);
    } catch (error) {
      console.error("Failed to fetch tasks:", error);
    }
  };

  const completeTask = async (taskId) => {
    try {
      const response = await fetch(
        `${API_URL}/tasks/${taskId}/complete`,
        {
          method: "PUT",
          credentials: "include",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            completed: true,
          }),
        }
      );

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || "Failed to complete task");
      }

      setTasks((currentTasks) =>
        currentTasks.map((task) =>
          task.id === taskId ? data : task
        )
      );
    } catch (error) {
      console.error("Failed to complete task:", error);
    }
  };

  const deleteTask = async (taskId) => {
    try {
      const response = await fetch(`${API_URL}/tasks/${taskId}`, {
        method: "DELETE",
        credentials: "include",
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || "Failed to delete task");
      }

      setTasks((currentTasks) =>
        currentTasks.filter((task) => task.id !== taskId)
      );
    } catch (error) {
      console.error("Failed to delete task:", error);
    }
  };

  useEffect(() => {
    fetchTasks();
  }, []);

  return (
    <div className="tasks-page">
      <h1>Tasks</h1>

      <p>Manage your tasks here.</p>

      <TaskForm onTaskCreated={fetchTasks} />

      <div className="task-list">
        <h2>Your Tasks</h2>

        {tasks.length === 0 ? (
          <p>No tasks yet.</p>
        ) : (
          tasks.map((task) => (
            <div className="task-card" key={task.id}>
              <h3>{task.title}</h3>

              <p>Priority: {task.priority}</p>

              <p>Duration: {task.estimatedMinutes} minutes</p>

              <p>
                Deadline:{" "}
                {task.dueDate
                  ? new Date(task.dueDate).toLocaleString()
                  : "No deadline"}
              </p>

              <p>
                Status: {task.completed ? "Completed" : "Active"}
              </p>

              {!task.completed && (
                <button onClick={() => completeTask(task.id)}>
                  Mark Complete
                </button>
              )}

              <button onClick={() => deleteTask(task.id)}>
                Delete
              </button>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

export default Tasks;